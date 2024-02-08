package org.example;


import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class UdpScp implements Runnable {
    Channel channel;
    InetSocketAddress target;


    AtomicInteger sendCount = new AtomicInteger();
    AtomicInteger recvCount = new AtomicInteger();


    int localPort;
    String targetIp;
    int targetPort;

    LinkedBlockingQueue<ByteBuf> queue = new LinkedBlockingQueue<>();
    LinkedBlockingQueue<ByteBuf> snd_queue = new LinkedBlockingQueue<>();

    public void send(ByteBuf buf) {
        snd_queue.add(Unpooled.copiedBuffer(buf));
    }

    private void udpSend(ByteBuf buf) {
        ByteBuf buf1 = byteBufAllocator.ioBuffer(1400);
        buf1.writeBytes(buf);

        channel.writeAndFlush(new DatagramPacket(buf1, target));
    }

    public UdpScp(int localPort, String targetIp, int targetPort) {
        this.localPort = localPort;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        target = new InetSocketAddress(targetIp, targetPort);
    }

    public void close() {
        group.shutdownGracefully();
        thread.interrupt();
    }

    EventLoopGroup group;
    Thread thread;

    public void start() {
        //创建udp服务器，如果收到数据 调用 receive


        // 配置事件循环器
//            EventLoopGroup group = new NioEventLoopGroup();
        group = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());

        try {
            // 启动NIO服务的引导程序类
            Bootstrap b = new Bootstrap();

            b.group(group) // 设置EventLoopGroup
                    .channel(NioDatagramChannel.class) // 指明新的Channel的类型
                    .option(ChannelOption.SO_BROADCAST, true) // 设置的Channel的一些选项
                    .handler(new DatagramChannelEchoServerHandler(this)); // 指定ChannelHandler
            ;
            // 绑定端口
            ChannelFuture f = b.bind(localPort).sync();
            System.out.println("DatagramChannelEchoServer已启动，端口：" + localPort);
            this.channel = f.channel();
            // 等待服务器 socket 关闭 。
            // f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            return;
//                throw new RuntimeException(e);
        } finally {
            // 优雅的关闭
//            group.shutdownGracefully();
        }

        thread = new Thread(this);
        thread.start();


    }

    public AtomicInteger totalRecvLen = new AtomicInteger();
    private ByteBufAllocator byteBufAllocator = ByteBufAllocator.DEFAULT;
    SCP scp = new SCP(1024 * 1024 * 10);//接收的缓冲区大小
    @Override
    public void run() {
        while (true) {
            ByteBuf buf;
            while ((buf = queue.poll()) != null) {
                scp.input(buf);//收到的数据  传入input里进行处理
                buf.release();
            }
            while ((buf = snd_queue.poll()) != null) {
                scp.send(buf);//写入发送缓冲区
                buf.release();
            }
            buf = Unpooled.buffer();
            int rcvLen=scp.recv(buf);
            if(rcvLen>0){
                totalRecvLen.addAndGet(rcvLen);//收到数据
            }

             for (int i = 0; i < 1000; i++) {
                buf.clear();
                int len = scp.takeSend(buf, 1400);
                if (len == 0) {

                    break;
                } else {
                    udpSend(buf);
                }
            }
            buf.release();
            if(queue.size()>50){
//                System.out.println("queue.size()="+queue.size());
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return;
            }
        }


    }


    public static class DatagramChannelEchoServerHandler extends ChannelInboundHandlerAdapter {
        UdpScp udpLink;

        public DatagramChannelEchoServerHandler(UdpScp link) {
            this.udpLink = link;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            udpLink.recvCount.incrementAndGet();
            DatagramPacket packet = (DatagramPacket) msg;
            udpLink.queue.add(packet.content());
        }
    }
}

