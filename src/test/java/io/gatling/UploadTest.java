package io.gatling;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.Assert;
import org.junit.Test;

public class UploadTest {

    public static final File TMP_DIR = new File(System.getProperty("java.io.tmpdir"), "netty-issue-" + UUID.randomUUID().toString().substring(0, 8));
    public static final byte[] PATTERN_BYTES = "FooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQix".getBytes(Charset.forName("UTF-16"));
    public static final File FILE;

    static {
        TMP_DIR.mkdirs();
        TMP_DIR.deleteOnExit();

        long repeats = 500 * 1024 / PATTERN_BYTES.length + 1;
        try {
            FILE = File.createTempFile("tmpfile-", ".data", TMP_DIR);
            FILE.deleteOnExit();
            try (OutputStream out = Files.newOutputStream(FILE.toPath())) {
                for (int i = 0; i < repeats; i++) {
                    out.write(PATTERN_BYTES);
                }

                long expectedFileSize = PATTERN_BYTES.length * repeats;

                if (FILE.length() != expectedFileSize) {
                    throw new ExceptionInInitializerError("Invalid file length");
                }

            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private void addHander(ChannelPipeline pipeline, CompletableFuture<HttpResponseStatus> result) {
        pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {

            private boolean responseReceived;

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                try {
                    if (msg instanceof HttpResponse) {
                        responseReceived = true;
                        HttpResponse response = (HttpResponse) msg;
                        ctx.channel().close();
                        result.complete(response.status());
                    }

                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                if (!responseReceived) {
                    result.completeExceptionally(cause);
                }
            }
        });
    }

    private HttpResponseStatus sendRequest(NettyClient nettyClient, int port, Function<File, Object> bodyGenerator) throws Throwable {

        CompletableFuture<HttpResponseStatus> result = new CompletableFuture<>();

        nettyClient.connect(9999).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                if (f.isSuccess()) {
                    Channel channel = f.channel();

                    addHander(channel.pipeline(), result);

                    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/");
                    request.headers()//
                            .set(CONTENT_LENGTH, FILE.length())//
                            .set(HOST, "localhost");

                    f.channel().write(request);
                    f.channel().write(bodyGenerator.apply(FILE)).addListener(new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(Future<? super Void> f) throws Exception {
                            if (f.cause() != null) {
                                result.completeExceptionally(f.cause());
                            }
                        }
                    });
                    f.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, f.channel().voidPromise());
                } else {
                    result.completeExceptionally(f.cause());
                }
            }
        });

        return result.get(10, TimeUnit.SECONDS);
    }

    private void test(Function<File, Object> bodyGenerator) throws Throwable {
        int port = 9999;
        try (JettyServer jettyServer = new JettyServer(port)) {
            try (NettyClient nettyClient = new NettyClient()) {
                for (int i = 0; i < 5; i++) {
                    HttpResponseStatus responseStatus = sendRequest(nettyClient, port, bodyGenerator);
                    System.out.println("i=" + i + " responseStatus=" + responseStatus);
                    Assert.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), responseStatus.code());
                }
            }
        }
    }

    @Test
    public void testFileRegion() throws Throwable {
        test(file -> new DefaultFileRegion(file, 0, file.length()));
    }

    @Test
    public void testChunkedFile() throws Throwable {
        test(file -> {
            try {
                return new ChunkedFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testChunkedNioFile() throws Throwable {
        test(file -> {
            try {
                return new ChunkedNioFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
