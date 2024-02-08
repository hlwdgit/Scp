package org.example;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class UdpScpTest {
    public static void main(String[] args) throws InterruptedException {
        UdpScp scp1 = new UdpScp(7777, "127.0.0.1", 8888);
        UdpScp scp2 = new UdpScp(8888, "127.0.0.1", 7777);

        scp1.start();
        scp2.start();


        int testLen=1024*1024*100;
        ByteBuf data= Unpooled.buffer();
        data.writeBytes(new byte[testLen]);
        scp1.send(data);
        Thread.sleep(1000);
       long st=System.currentTimeMillis();
       int count=0;
        while(true){
//            scp1.scp.rto=60000;//rto有问题 复制的kcp 的 暂时不研究了
//            scp2.scp.rto=60000;//rto有问题 复制的kcp 的 暂时不研究了 （我不会）
            if(scp2.totalRecvLen.get()>=testLen){
//                scp2.totalRecvLen.addAndGet(-testLen);
                System.out.println("接收完毕 耗时:"+(System.currentTimeMillis()-st));
                scp2.totalRecvLen.addAndGet(-testLen);
                data.resetReaderIndex();
                scp1.send(data);
//                break;
                st=System.currentTimeMillis();
                count++;
                if(count==100){
                    break;
                }
            }

            Thread.sleep(100);
        }
        scp1.close();
        scp2.close();


    }
}
