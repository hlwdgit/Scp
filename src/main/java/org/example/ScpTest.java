package org.example;

import io.netty.buffer.*;
import junit.framework.TestCase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class ScpTest extends TestCase {

    @Test
    public void test接收() {
        SCP lcp = new SCP();


        lcp.send(Unpooled.copiedBuffer("你好".getBytes()));
        ByteBuf buf = Unpooled.buffer();
        lcp.takeSend(buf, 30);
        SCP l2 = new SCP();
        l2.input(buf);

        ByteBuf recBuf = Unpooled.buffer();
        int recv = l2.recv(recBuf);
        System.out.println("recv = " + recv);

        for (int i = 0; i < 10; i++) {
            lcp.send(Unpooled.copiedBuffer("你好".getBytes()));
            buf = Unpooled.buffer();
            lcp.takeSend(buf, 30);

            l2.input(buf);

            if (Math.random() < 0.5) {
                recv = l2.recv(recBuf);
                System.out.println("recv = " + recv);
            }

        }
        recv = l2.recv(recBuf);
        System.out.println("recv = " + recv);

    }

    @Test
    public void testBufSetWeiterIndex() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(new byte[5120]);
        buf.writerIndex(999);

    }

    @Test
    public void testBug() {
        SCP lcp = new SCP();
        SCP.Payload payload = new SCP.Payload();
        payload.length = 258;
        payload.offset = 196860;
        lcp.rcv_offset = 196896;
        lcp.rcvBufWrite(payload);

    }

    @Test
    public void test丢包超时重传() throws InterruptedException, IOException {
        SCP lcp = new SCP();
        Random rand = new Random(4);


        lcp.send(Unpooled.copiedBuffer("你好".getBytes()));
        ByteBuf buf = Unpooled.buffer();
        lcp.takeSend(buf, 30,true);
        SCP l2 = new SCP();
        l2.rmt = lcp;
        lcp.rmt = l2;
        l2.name = "l2";
        lcp.name = "lcp";
        l2.input(buf);

        ByteBuf recBuf = Unpooled.buffer();
        int recv = l2.recv(recBuf);
        System.out.println("recv = " + recv);
        for (int i = 0; i < 200; i++) {
            lcp.send(Unpooled.copiedBuffer("你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好".getBytes()));
            l2.send(Unpooled.copiedBuffer("你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好".getBytes()));

        }
        List<ByteBuf> queue = new ArrayList<>();//模拟乱序 随机取出

        boolean pause = false;
        for (int i = 0; i < 10000; i++) {

            if (i < 200) {
//                lcp.send(Unpooled.copiedBuffer("你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好".getBytes()));

            }
            if (pause == true) {
                //判断l2 为什么收不到153之后的
                //查看没收到的部分
                SCP.Segment first = lcp.snd_segments.getFirst();
                for (SCP.Payload payload : first.payloads_stream) {
                    if (l2.isRecived(payload.offset, payload.length) == false) {
                        System.out.println("未收到" + payload.offset + payload.length);
                    }
                }


            }
            if (i % 10 == 0) {

                lcp.send(Unpooled.copiedBuffer("你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好你好".getBytes()));

            }
            if (lcp.real_rcv_offset == 4096) {
                System.out.println("lcp缓冲区满" + i);
            }
            buf = Unpooled.buffer();
            if (i > 50) {
                System.out.println("lcp是否需要发送:" + lcp.isNeedSend());
                lcp.takeSend(buf, 3000,true);
                System.out.println("包大小" + buf.writerIndex());
                queue.add(buf);
            } else {
                lcp.takeSend(buf, 30);
                queue.add(buf);
            }
            if (queue.size() > 10) {
                queue.removeFirst();
            }
            if (rand.nextDouble() < 0.7) {

                buf.markReaderIndex();
                SCP.Segment segment = SCP.Segment.readFromBuf(buf);
                buf.resetReaderIndex();
                System.out.println("模拟丢包" + segment.sn);

            } else {

                try {
                    buf = queue.get(rand.nextInt(queue.size()));
                    queue.remove(buf);
                    buf.markReaderIndex();
                    SCP.Segment segment = SCP.Segment.readFromBuf(buf);
                    buf.resetReaderIndex();
                    System.out.println("lcp发送" + segment.sn);

                    l2.input(buf);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }


            if ((i % 1) == 0) {
                buf = Unpooled.buffer();
                l2.takeSend(buf, 100,true);
                System.out.println("反馈");
                lcp.input(buf);
                buf = Unpooled.buffer();
//                lcp.recv(buf);
            }
            if (recBuf.writerIndex() > 25000) {
                byte[] bytes = new byte[recBuf.readableBytes()];
                recBuf.markReaderIndex();
                recBuf.readBytes(bytes);
                recBuf.resetReaderIndex();
                System.out.println(new String(bytes));
                Files.write(Path.of("D:/write"), bytes);

            }


            if (rand.nextDouble() < 0.5) {
                recv = l2.recv(recBuf);
                System.out.println("recv = " + recv);
            }
            Thread.sleep(200);
        }
        recv = l2.recv(recBuf);
        System.out.println("recv = " + recv);


    }

    @Test
    public void testDataRangeTs() throws InterruptedException {
        DR dr = new DR();
        dr.add(10);
        dr.markRange(0, 10, DR.UNACK);
        dr.last().rto = 5000;
        dr.last().ts = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            List<DR.Range> rs = dr.needRanges(10, DR.UNACK, System.currentTimeMillis());

            for (DR.Range r : rs) {
                System.out.println((System.currentTimeMillis() - r.rto));
            }
            Thread.sleep(1000l);
        }


    }

    @Test
    public void test丢包() throws InterruptedException {
        SCP lcp = new SCP();


        lcp.send(Unpooled.copiedBuffer("你你好你好你好".getBytes()));
        ByteBuf buf = Unpooled.buffer();
        lcp.takeSend(buf, 30);
        SCP l2 = new SCP();
        lcp.rmt = l2;
        l2.rmt = lcp;
        l2.input(buf);
        //丢包
        lcp.takeSend(buf, 30);
        buf.readerIndex(0);
        buf.writerIndex(0);

        lcp.takeSend(buf, 30);
        l2.input(buf);
        ByteBuf recBuf = Unpooled.buffer();

        int recv = l2.recv(recBuf);
        System.out.println("recv = " + recv);
    }

    @Test

    public void testResend() {
        int bufSize = 1024 * 4;
        //测试发送文件
        SCP l2 = new SCP(bufSize);
        SCP lcp = new SCP(bufSize);
        l2.rmt = lcp;
        lcp.rmt = l2;


        lcp.name = "lcp";
        l2.name = "l2";
        ByteBuf data = Unpooled.buffer();
        data.writeBytes(new byte[1024 * 8]);
        lcp.send(data);
        for (int i = 0; i < 10; i++) {
            ByteBuf buf = Unpooled.buffer();
            int len = lcp.takeSend(buf, 1400);
            if (len < 1) {
                break;
            }
            System.out.println("lcp发送长度" + len);
            if(i==0||i==2){
                continue;//丢包
            }
            l2.input(buf);l2.recv(Unpooled.buffer());
        }
        System.out.println("准备反馈");
        //反馈
        {
            ByteBuf buf = Unpooled.buffer();
            int len = l2.takeSend(buf, 1400);
            lcp.input(buf);
            System.out.println("l2发送长度" + len);
            System.out.println(lcp.getUnackSegCount()+" 未确认数量");
            buf.clear();
//            len=lcp.takeSend(buf, 1400);
//            System.out.println("lcp发送长度"+len);
//            l2.input(buf);l2.recv(Unpooled.buffer());
//            buf.clear();
            len=lcp.takeSend(buf, 1400);
            System.out.println("lcp发送长度"+len);
            l2.input(buf);l2.recv(Unpooled.buffer());
            buf.clear();
            //需要反馈
            System.out.println("接收到重发后的反馈");
            len = l2.takeSend(buf, 1400);
            lcp.input(buf);
            System.out.println("l2发送长度" + len);
        }

        System.out.println("前4K应该完毕了,测试能不能发送后4K");
        for (int i = 0; i < 10; i++) {
            ByteBuf buf = Unpooled.buffer();
            int len = lcp.takeSend(buf, 1400);
            if (len < 1) {
                break;
            }
            System.out.println("lcp发送长度" + len);

            l2.input(buf);

        }

        System.out.println(l2.real_rcv_offset);
        System.out.println(l2.real_rcv_offset);


    }

    @Test
    public void testSendFile() throws IOException, InterruptedException {
        int bufSize = 1024 * 1024 * 4;
        //测试发送文件
        SCP l2 = new SCP(bufSize);
        SCP lcp = new SCP(bufSize);
        l2.rmt = lcp;
        lcp.rmt = l2;

        lcp.name = "lcp";
        l2.name = "l2";
        ByteBuf data = Unpooled.buffer();
        lcp.snd_offset=Integer.MAX_VALUE-1024*1024*40;
        l2.snd_offset=Integer.MAX_VALUE-1024*1024*40;
        lcp.rmt_rcv_offset=lcp.snd_offset;
        l2.rmt_rcv_offset=l2.snd_offset;
        lcp.rcv_offset=lcp.snd_offset;
        l2.rcv_offset=l2.snd_offset;
        lcp.rmt_real_rcv_offset=lcp.snd_offset;
        l2.rmt_real_rcv_offset=l2.snd_offset;
        lcp.real_rcv_offset=lcp.snd_offset;
        l2.real_rcv_offset=l2.snd_offset;

        Random rand=new Random(1);
        int testSize = 1024 * 1024 * 1000;
//        RandomAccessFile rafSource = new RandomAccessFile("D:/testdata", "rw");
//        FileChannel sourceChannel = rafSource.getChannel();
//        data.writeBytes(sourceChannel, 0L, (int) sourceChannel.size());
//        data.writeBytes(sourceChannel, 0L, testSize);
//        RandomAccessFile rafDownload = new RandomAccessFile("D:/testdata.download", "rw");


//        lcp.send(data);
//        testSize=lcp.snd_buf.writerIndex();
        ByteBuf rcvBuf = Unpooled.buffer();
        int rcvTotal = 0;
        ByteBuf buf = Unpooled.buffer();
        long st = System.currentTimeMillis();
        int i = 0;
        for (; i <2000000; i++) {
            buf.clear();
//            System.out.println("一轮耗时" + (System.currentTimeMillis() - st));
//            st = System.currentTimeMillis();
            if (lcp.snd_buf.writerIndex() < 4096) {
//                System.out.println("snd_buf大小" + lcp.snd_buf.writerIndex());
//                System.out.println("不需要发送");
                byte[] bytes = new byte[1024 * 128];
//                int readLen=rafSource.read(bytes);
//                if(readLen<1){
//                    System.out.println("readLen"+readLen+"  i"+i);
//                    return;
//                }
                if (testSize <= 0) {
                    System.out.println("i    " + i);
                    return;
                }
//                testSize -= bytes.length;
                lcp.send(Unpooled.wrappedBuffer(bytes));
            }
            {

//                System.out.println(i);
//                st = System.currentTimeMillis();
                lcp.takeSend(buf, 1400,true);

//                System.out.println(" lcp takesend耗时" + (System.currentTimeMillis() - st));
                if (buf.writerIndex() == 0) {//没取出
                    System.out.println("lcp发送失败");
                    Thread.sleep(10);
                }
//                st = System.currentTimeMillis();
//                if(rand.nextDouble()<0.8){
//                    l2.input(buf);//模拟丢包
//                }
                l2.input(buf);

//                System.out.println(" 耗时" + (System.currentTimeMillis() - st));
                int rcvlen = l2.recv(rcvBuf);
                if (rcvlen > 0) {
                    rcvTotal += rcvlen;
//                    System.out.println("i"+i+"  rcvlen = " + rcvlen);
                    testSize -= rcvlen;
//                    System.out.println(testSize);
                    byte[] bytes = new byte[rcvlen];
                    rcvBuf.readBytes(bytes);

                    rcvBuf.clear();
//                    rcvBuf.discardReadBytes();
                }

//
//                rafDownload.write(bytes,0,rcvlen);
//                System.out.println("i写出耗时" + (System.currentTimeMillis() - st));
//                System.out.println("rcvlen = " + rcvlen);
            }
            if (testSize < 0) {
                System.out.println("testSize<0  " + testSize);
            }
            if (testSize <= 0) {

                System.out.println("耗时=" + (System.currentTimeMillis() - st));
                System.out.println("测试结束" + i);
                break;
            }
            if (i == 48849) {
                System.out.println("i==94");
            }

            buf = Unpooled.buffer();

            l2.takeSend(buf, 1400,true);
            if (buf.writerIndex() > 0) {
                lcp.input(buf);
//                System.out.println("..."+l2.snd_next_sn);
//                System.out.println("发送"+i);
            } else {
//                System.out.println("不需要发送");
            }


        }
        System.out.println("收到" + rcvTotal);
        System.out.println("结束" + i);
        System.out.println("TESTSIZE  " + testSize);

    }

    ByteBuf buf;

    @Test
    public void testSlice() {
        //测试slice能不能替代discard


        int cap = 1024 * 1024;
        buf = Unpooled.wrappedBuffer(new byte[cap], new byte[cap]);
        byte[] data = new byte[cap];
        buf.writeBytes(data);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 128);
        }
        byte[] readBuf = new byte[1400];
        for (int i = 0; i < 10; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(() -> {
//                if(buf.writeBytes()< readBuf.length){
//                    buf = Unpooled.wrappedBuffer(new byte[cap],new byte[cap]);
//                    CompositeByteBuf cbuf=((CompositeByteBuf)buf);
////                    ((CompositeByteBuf)buf).addComponent(Unpooled.wrappedBuffer(new byte[cap*2-buf.capacity()]));
////                    capCount++;
//                }
//                buf.readBytes(readBuf);
//                buf.discardReadBytes();
//                buf.writeBytes(readBuf);

                ByteBuf slice = buf.slice(readBuf.length, buf.writerIndex());
                buf.readerIndex(0);
                buf.writerIndex(0);
                buf.writeBytes(slice);


//                buf.writeInt(1);

//                buf.writerIndex(buf.writerIndex()+readBuf.length);
            }, null, 1000L);
            System.out.println("扩容次数" + capCount);
            capCount = 0;
            System.out.println("test.times = " + test.times);
            System.out.println(data.length * test.times / 1024.0 / 1024 / 1024);
        }
    }

    int capCount = 0;

    @Test
    public void testBufDiscard() {
//        ByteBuf buf = Unpooled.buffer();
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[1], new byte[1]);
        byte[] data = new byte[1024 * 4];
        buf.writeBytes(data);
        int cap = 1024 * 1024;

        for (int i = 0; i < 10; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(() -> {
                buf.readInt();


                buf.discardReadBytes();

                buf.writeInt(1);
                if (buf.capacity() < cap) {
                    CompositeByteBuf cbuf = ((CompositeByteBuf) buf);
                    ((CompositeByteBuf) buf).addComponent(Unpooled.wrappedBuffer(new byte[cap * 2 - buf.capacity()]));
//                    ((CompositeByteBuf)buf).addComponent(Unpooled.wrappedBuffer(new byte[4]));
//                    CompositeByteBuf byteBufs = ((CompositeByteBuf) buf).discardReadComponents();
//                    if(byteBufs!=null){
//                        System.out.println(byteBufs.capacity());
//                        cbuf.addComponent(byteBufs);


//                    buf.capacity(cap);
                    capCount++;
                }


            }, null, 1000L);
            System.out.println("扩容次数" + capCount);
            capCount = 0;
            System.out.println("test.times = " + test.times);
            System.out.println(data.length * test.times / 1024.0 / 1024 / 1024);
        }

    }

    @Test
    public void testCompoit() {
        ByteBuf[] bufs = new ByteBuf[2];
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = Unpooled.buffer();
        }
        ByteBuf byteBuf = Unpooled.wrappedBuffer(bufs);
        byte[] bytes = new byte[1024 * 1024];
        byteBuf.writeBytes(bytes);
        System.out.println(byteBuf.readableBytes());
    }

    @Test
    public void testDR() throws InterruptedException {

        System.out.println("DataRange.isOverlap(0,10,5,20) = " + DR.isOverlap(0, 10, 5, 20));
        DR dr = new DR();
        dr.add(2000);
        dr.markRange(0, 100, DR.UNACK, 3000);

        {
            DR.Range range = dr.needOne(100, 300, DR.UNACK, DR.UNSEND, System.currentTimeMillis());
            System.out.println(range);
            if (range != null) {
                dr.markRange(range.start, range.length, DR.UNACK, 4000);
            }
        }
        {
            DR.Range range = dr.needOne(100, 300, DR.UNACK, DR.UNSEND, System.currentTimeMillis());
            System.out.println(range);
            if (range != null) {
                dr.markRange(range.start, range.length, DR.UNACK, 4000);
            }
        }
        {
            DR.Range range = dr.needOne(100, 300, DR.UNACK, DR.UNSEND, System.currentTimeMillis());
            System.out.println(range);
            if (range != null) {
                dr.markRange(range.start, range.length, DR.UNACK, 4000);
            }

        }
        Thread.sleep(1000);
        {
            DR.Range range = dr.needOne(100, 300, DR.UNACK, DR.UNSEND, System.currentTimeMillis());
            System.out.println(range);
        }


    }
    @Test
    public void testNeedOne(){
        DR dr = new DR();
        dr.add(1000);
        Long curTs=System.currentTimeMillis();
        DR.Range range = dr.needOne(100, 1000, DR.UNACK, DR.UNSEND, curTs);
        range.rto=30000;range.ts=curTs;range.type= DR.UNACK;range.sendcount=1;
        System.out.println(range);
        curTs+=2;
        DR.Range range2 = dr.needOne(100, 1000, DR.UNACK, DR.UNSEND, curTs);
        range2.rto=30000;range2.ts=curTs;range2.type= DR.UNACK;
        System.out.println(range2);
        System.out.println(range2==range);

    }
    @Test
    public void testDR2(){
        DR dr = new DR();
        dr.add(1024 * 1024 * 100);

        byte[] data=new byte[4096];
        long curTs=System.currentTimeMillis();
        int rto=3000;
        DR.Range range = dr.needOneUseCache(1376, 1024 * 1024 * 100, DR.UNACK, DR.UNSEND, curTs);
//                dr.markRangen(range.start,range.length,DataRange.UNACK,300);
        range.ts = curTs;
        range.rto = rto;
        System.out.println(range.start);

        range = dr.needOneUseCache(1376, 1024 * 1024 * 100, DR.UNACK, DR.UNSEND, curTs);
        System.out.println(range.start);

        SCP.Segment segment = SCP.Segment.createSegment();
        segment.payloads_stream.add(new SCP.Payload(0,1376,Unpooled.wrappedBuffer(new byte[1376])));
        ByteBuf buf=Unpooled.buffer();
        System.out.println(dr.ranges.size());
        byte[] bitarry=new byte[8];
        for (int i = 0; i < 10; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(()->{
//                buf.clear();
//                segment.writeToBuf(buf);
                for (int j = 0; j < bitarry.length*8; j++) {
                    AckMaskUtils.getBitInByteArray(j, bitarry);

                }

            },null,1000L);
            System.out.println("test.times = " + test.times);

        }
    }
    @Test
    public void testDRPerformance(){
        DR dr = new DR();
        dr.add(1024 * 1024 * 100);

        byte[] data=new byte[4096];
        long curTs=System.currentTimeMillis();
        AtomicInteger idx=new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(()->{
                DR.Range range = dr.needOneUseCache(1376, 1024 * 1024 * 100, DR.UNACK, DR.UNSEND, System.currentTimeMillis());
//                if(range==null){
////                    System.out.println("失败");
//                }else {
//                    range.type = DataRange.ACK;
//                }
//                Arrays.copyOf(data,  8);
//                dr.splitn(idx.getAndIncrement()*1 );

                dr.markRangen(range.start,range.length, DR.UNACK,300);
            },null,1000L);
            System.out.println("test.times = " + test.times);
            dr.leftShift(dr.getSize());
            dr.add(1024 * 1024 * 100);
            System.out.println(DR.sumTotalSize(dr.ranges));
        }

        System.out.println(dr.ranges.size());

    }
    @Test
    public void testDRPerformance2(){
        DR dr = new DR();
        dr.add(1024 * 1024 * 100);

        byte[] data=new byte[4096];
        long curTs=System.currentTimeMillis();
        AtomicInteger idx=new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(()->{
                SCP.Segment.createSegment();
            },null,1000L);
            System.out.println("test.times = " + test.times);
            dr.leftShift(dr.getSize());
            dr.add(1024 * 1024 * 100);
            System.out.println(DR.sumTotalSize(dr.ranges));
        }

        System.out.println(dr.ranges.size());

    }


    @Test
    public void testJiya() throws InterruptedException {
        int bufSize = 1024 * 1024 * 320;
        //测试发送文件
        SCP l2 = new SCP(bufSize);
        SCP lcp = new SCP(bufSize);
//        l2.rmt = lcp;
//        lcp.rmt = l2;


        lcp.name = "lcp";
        l2.name = "l2";

        RingArrayList<ByteBuf> arr=new RingArrayList();

        lcp.send(Unpooled.wrappedBuffer(new byte[1024*1024*100]));

        Long  st=System.currentTimeMillis();
        for (int i = 0; i < 60000; i++) {
            ByteBuf buf=Unpooled.buffer();
            int len = lcp.takeSend(buf, 1400);
            if(len==0){
                System.out.println("发送次数"+i);
                break;
            }
            arr.add(buf);
//            LCP.Segment segment = LCP.Segment.readFromBuf(buf);

//            DataRange.Range range = lcp.snd_dr.needOneUseCache(1, 999999, DataRange.UNACK, DataRange.UNSEND, System.currentTimeMillis());

//            range.ts=System.currentTimeMillis();
//            range.rto=3000;
//            range.type=DataRange.UNACK;
//            range.sendcount++;
        }
        System.out.println("耗时："+(System.currentTimeMillis()-st));
        st=System.currentTimeMillis();
        for (int i = 0; i < arr.size();  ) {
            ByteBuf buf1 = arr.removeFirst();

            l2.input(buf1);

            buf1.release();

            if(i%10 ==0){
                l2.recv(Unpooled.buffer());
            }

        }

        System.out.println("耗时："+(System.currentTimeMillis()-st));



    }


    @Test
    public void testLoseStrayBug() {
        int bufSize = 1024 * 1024 * 320;
        //测试发送文件
        SCP l2 = new SCP(bufSize);
        SCP lcp = new SCP(bufSize);
        l2.rmt = lcp;
        lcp.rmt = l2;


        lcp.name = "lcp";
        l2.name = "l2";

        RingArrayList<ByteBuf> arr=new RingArrayList();

        lcp.send(Unpooled.wrappedBuffer(new byte[1375*3]));

        Long  st=System.currentTimeMillis();
        //测试 丢包 之后的停滞问题

        System.out.println("LCP._itimediff(2,0) = " + SCP._itimediff(2, 0));
        for (int i = 0; i < 6; i++) {
            ByteBuf buf = Unpooled.buffer();
            if(i==4){
                System.out.println("i..."+i);
            }
            int l = lcp.takeSend(buf, 1400);
            if(l<20){
                System.out.println("lcp发送失败"+i);
            } else{
                System.out.println("lcp send"+l+"  i "+i);

                if(i==0||i==2){
                    System.out.println("丢失");
                    //丢失
                }else{
                    l2.input(buf);
                    buf.clear();
                    int i1 = l2.takeSend(buf, 1400,true);

                    if(i1>0){
                        lcp.input(buf);
                    }else{
                        System.out.println("l2发送失败");
                    }
                }

            }
        }


        System.out.println("耗时："+(System.currentTimeMillis()-st));

    }
}