package io.gatling;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

public class NettyClient implements AutoCloseable {

    private final NioEventLoopGroup clientGroup;
    private final Bootstrap clientBootstrap;

    public NettyClient() {
        clientGroup = new NioEventLoopGroup();
        clientBootstrap = new Bootstrap()//
                .group(clientGroup)//
                .channel(NioSocketChannel.class)//
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline()//
                                .addLast("client", new HttpClientCodec())//
                                .addLast("chunking", new ChunkedWriteHandler());
                    }
                });
    }

    public ChannelFuture connect(int port) {
        return clientBootstrap.connect("localhost", port);
    }

    @Override
    public void close() {
        clientGroup.shutdownGracefully();
    }
}
