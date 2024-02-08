package org.example;



import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.Recycler;
import org.junit.Assert;

import java.util.*;

public class SCP {

    public final int IKCP_RTO_NDL = 30;   // no delay min rto
    public final int IKCP_RTO_MIN = 1000;  // normal min rto
//    public final int IKCP_RTO_MIN = 10000;  // normal min rto

    public final int IKCP_RTO_DEF = 200;
    public final int IKCP_RTO_MAX = 60000;
    long rx_srtt = 0;
    long rx_rttval = 0;
    long rx_rto = IKCP_RTO_DEF;
    long rx_minrto = IKCP_RTO_MIN;

    void update_rtt(int rtt) {
//        System.out.println("rtt"+rtt);
        if (0 == rx_srtt) {
            rx_srtt = rtt;
            rx_rttval = rtt / 2;
        } else {
            int delta = (int) (rtt - rx_srtt);
            if (0 > delta) {
                delta = -delta;
            }

            rx_rttval = (3 * rx_rttval + delta) / 4;
            rx_srtt = (7 * rx_srtt + rtt) / 8;
            if (rx_srtt < 1) {
                rx_srtt = 1;
            }
        }

        int rto = (int) (rx_srtt + _imax_(1, 4 * rx_rttval));
        rx_rto = _ibound_(rx_minrto, rto, IKCP_RTO_MAX);
    }


    //
    public String name;
    public SCP rmt;

    static int OVERHEAD = 1 + 4 + 4 + 4 + 4;

    int snd_offset;
    public int rcv_offset;//这个是用来避免 对方发送超出 缓冲区大小的
    //    CompositeByteBuf snd_buf;//需要注意释放
    Buf snd_buf;//需要注意释放
    //    CompositeByteBuf rcv_buf;//需要注意释放
    Buf rcv_buf;//需要注意释放
    //    ByteBuf rcv_buf = Unpooled.buffer();//需要注意释放
    public int rto = 10000;
    int resend = 1;//快速重传 被跳过阈值

    int rcv_buf_size = 4096;//发送时 数据不能超过缓冲区
    //    int snd_buf_size = 4096;//接收时数据不能超过缓冲区

    long emptyPackInterval = 100;
    int maxIgnoreCount = 4;
    int ignoreCount = maxIgnoreCount;//如果达到 阈值,忽略间隔,可以直接发送数据  否则 没数据发takeSend 在间隔内不会多次打包

    int snd_next_sn = 0;//下一个sn序号
    int rcv_next_sn = 0;//下一个 应该接收的sn  也就是 发送时的una
    int rmt_una = rcv_next_sn;//远程 未确认的第一个 本方 发送序号
    int rmt_max_acksn;//记录最大被确认的acksn  用于快速重传 小于的全部被跳过次数+1
    public DR snd_dr = new DR();
    DR rcv_dr = new DR();
    //    public List<Segment> snd_segments = new ArrayList<>();
    public List<Segment> snd_segments = new RingArrayList<>();

    public static int CMD_PACKET_STREAM = 1;//有流
    public static int CMD_PACKET_ACKMASK = 2;//有ACKMASK
    public static int CMD_PACKET_ACKMASK_COMPRESS=4;//压缩的ACKMASK

    boolean needUpdate = false;
    public int stats_resendCount = 0;
    public int stats_repeatDataCount = 0;

    public SCP() {
        this(4096);

    }

    public SCP(int rcv_buf_size) {
        //snd_buf_size是多余的
//        rmt_real_rcv_offset *= 2;//debug//如果发送的是real_rcv_offset 则需要扩充
//        snd_buf.capacity(snd_buf_size);
        this.rcv_buf_size = rcv_buf_size;
        //4K一个
        {
            int remain = rcv_buf_size;
//        snd_buf= (CompositeByteBuf) Unpooled.wrappedBuffer(new byte[4096],new byte[4096]);
//        snd_buf.clear();
            remain -= 4096 * 2;
//        while (remain>0){
//            snd_buf.addComponent(Unpooled.wrappedBuffer(new byte[4096]));
//            remain-=4096;
//        }
        }
        rcv_buf = new Buf();
        snd_buf = new Buf();
        rcv_buf.capacity(this.rcv_buf_size);
//        rcv_buf.capacity(this.rcv_buf_size);
//        rcv_buf.capacity(this.rcv_buf_size);
        {
            int remain = rcv_buf_size;
//            rcv_buf= (CompositeByteBuf) Unpooled.wrappedBuffer(new byte[rcv_buf_size],new byte[4096]);
//            rcv_buf.clear();

//            rcv_buf.ensureWritable(rcv_buf_size);
            remain -= 4096 * 2;
//            while (remain>0){
//                rcv_buf.addComponent(Unpooled.wrappedBuffer(new byte[4096]));
//                remain-=4096;
//            }
        }


        //debug

//        snd_offset=Integer.MAX_VALUE-1024*1024*40;
//        rmt_rcv_offset=snd_offset;
//        rmt_real_rcv_offset=snd_offset;
//        rcv_offset=snd_offset;
//        real_rcv_offset=snd_offset;


        preSendTime = System.currentTimeMillis() - emptyPackInterval;
    }

    public int sndBufWriteToBuf(ByteBuf out, int offset, int len) {
        int o = _itimediff(offset, snd_offset);//实际buf偏移
        //判断是否有数据需要发送
        try {
            if (o >= 0) {
                if (o + len <= snd_buf.writerIndex()) {//够
                    snd_buf.readerIndex(o);
                    snd_buf.readBytes(out, len);
                    return len;
                }
                throw new RuntimeException("从snd_buf读取时出错 不够");
            } else {
                System.out.println("忽略过时的offset" + offset + "  当前" + snd_offset);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return 0;
    }

    public void rcvBufWrite(Payload payload) {
        int diff = _itimediff(payload.offset + payload.length, rcv_offset);
        if (diff <= 0) {//检查 是否在范围
            System.out.println("忽略过时的" + payload.offset + " diff" + diff);
            if(payload.offset<0){
                //暂停
                System.out.println("暂停  rcv_offset 负数");
            }
            stats_repeatDataCount++;
            return;//
        }
        //计算读取的长度

        int offset = _maxint_(payload.offset, rcv_offset);
//        if (_itimediff(payload.offset, rcv_offset) > 0) {
//            offset = payload.offset;
//            ;
//        } else {
//            offset = rcv_offset;
//        }
        int paylaodOffset = _itimediff(offset, payload.offset);
        int needLen = _itimediff(payload.offset + payload.length - paylaodOffset, offset);
        if (needLen <= 0) {
            return;
        }
        diff = (_itimediff(offset, rcv_offset));//rcv_buf偏移
        if (diff < 0) {
            System.out.println("不应该进这里");

        }

        if (diff + needLen > rcv_dr.getSize()) {//超过当前rcv_dr范围 则增加
            int add = diff + needLen - rcv_dr.getSize();
            rcv_dr.add(add);//增加长度(中间可能有空的 所以不能add方法里标记)
            //添加并标记为已接收
            log("rcv_dr 增加" + add);
        }
//        {
//            int testLen = rcv_dr.leftCanShiftDistance(DataRange.ACK);
//
//            Assert.assertEquals(testLen, rcv_buf.writerIndex());
//        }
        recvCheck();
        //标记为已接收
        rcv_dr.markRangen(diff, needLen, DR.ACK);


        //读取数据
        try {
//            if(rcv_buf.capacity()<rcv_buf_size){
//                rcv_buf.capacity(rcv_buf_size+1024*16);
//            }

            payload.data.readerIndex(paylaodOffset);
            int pos = rcv_buf.writerIndex();
            rcv_buf.writerIndex(diff);//
            rcv_buf.writeBytes(payload.data, needLen);
            rcv_buf.writerIndex(Math.max(rcv_buf.writerIndex(), pos));
            payload.data.release();
            payload.data = null;
            recvCheck();
            {
//                int testLen = rcv_dr.leftCanShiftDistance(DataRange.ACK);
//
//                Assert.assertEquals(testLen, rcv_buf.writerIndex());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void log(String msg) {
        if (check) {
            System.out.println(name + ":" + msg);
        }
//
    }

    public static void main(String[] args) {
        SCP lcp = new SCP();
        byte[] bytes = new byte[10];
        lcp.snd_buf.writeBytes(bytes);
        ByteBuf buf = Unpooled.buffer();
        lcp.sndBufWriteToBuf(buf, 0, 10);
        Assert.assertEquals(buf.readableBytes(), 10);
        //
        System.out.println("测试");


    }


    public void send(ByteBuf data) {
//        while(snd_buf.capacity()< snd_buf.writerIndex()+data.readableBytes()){
//            snd_buf.addComponent(Unpooled.wrappedBuffer(new byte[1024*1024]));
//        }


        snd_dr.add(data.readableBytes());
        snd_buf.writeBytes(data);
    }

    int preDataSn;

    static int statsAckmaskMaxLen;//统计最大长度

    int ackMaskMaxLen = 8;//ackmask 发送时 最大长度。在可使用容量有余的时候 无视限制
    long preSendTime = 0;


    public int needSendSize() {
        //检查太耗cpu,如果频繁检测.因为 需要检测 区间未确认的
        return _itimediff(rmt_rcv_offset, snd_offset);
    }

    public boolean isNeedSend() {
        //根据是否有数据
//        if(needSendSize()>0){
//            return true;
//        }
        //没有数据时,间隔时间内不需要发送
        if (System.currentTimeMillis() - preSendTime <= 100) {
            return false;
        }
        return true;
    }

    ByteBufAllocator byteBufAllocator = PooledByteBufAllocator.DEFAULT;
    //    ByteBufAllocator byteBufAllocator = ByteBufAllocator.DEFAULT;
    int sendCount = 0;//调试用 对照una为什么没动
    int inputCount;//input被调用的次数

    Map<Integer, Segment> snd_cache = new HashMap<>();

    public int takeSend(ByteBuf out, int needLen) {
        return takeSend(out, needLen, false);
    }

    public int wnd = 20000;//简单的控制发送窗口
    //表示允许存在的 未确认snd_segment数量
    //丢包或超时时.. wnd减小
    //1秒内 没有丢包发生时 增加
    long preLoseTs;
    long preWndAddTs;

    public int getUnackSegCount() {
        int count = 0;
        for (Segment seg : snd_segments) {
            if (seg.done == false && seg.resend == false) {//有问题...如果只是标记了,但是没真正发出去,不会被统计到
                count++;
            }
        }
        return count;
    }

    /**
     * 返回写出的数量
     */
    public int takeSend(ByteBuf out, int needLen, boolean ignoreEmptyPacketInterval) {

        wnd = Math.max(4, wnd);
        if (needLen < OVERHEAD) {
            return 0;
        }
        if ("scp2".equals(name)) {
            boolean b = true;
        }
        if ("scp1".equals(name)) {
            boolean b = true;
        }
        if (snd_segments.size() > 6000) {//临时加的 避免阻塞
            if (Math.random() < 0.5) {//概率是 粗劣的  避免都丢了，之后重发的确保有机会发出去。
                return 0;
            }
        }

        //una更新规则： 加上 snd_segmetns 第一个的 sn和当前rmt_una的差值
        //忘了为什么了 而且加了之后 大于 远程的una
        //好像需要加上.  收到过大的una 发现  snd_segmetns 第一个 大于 rmt_una很多.说明una未更新.
        //这个可以更新...不对
        if (snd_segments.size() > 0) {
            if (_itimediff(snd_segments.getFirst().sn, rmt_una) > 2) {
                System.out.println("???" + _itimediff(snd_segments.getFirst().sn, rmt_una));
            }

//            Assert.assertTrue(rmt_una<=snd_segments.getFirst().sn);//不能根据这个判断 之前没发数据  发数据 第一个包 rmt_una(上次计算的) 大1

            rmt_una = snd_segments.getFirst().sn;

        }
        long curTs = System.currentTimeMillis();
        curTs -= curTs % 10;
        Segment segment = Segment.createSegment();
        segment.sn = snd_next_sn;

        segment.una = rcv_next_sn;
        segment.ts = curTs;//发送时的时间 只有本地存
        segment.rcv_offset = rcv_offset;


        //如果 远程的real_rcv_offset=end_offset// 没有数据要发送的时候  也持续更新una
        int end_offset = snd_offset + snd_buf.writerIndex();


        if (end_offset == rmt_real_rcv_offset) {
            rmt_una = segment.sn + 1;
        }
        segment.updateUna = rmt_una;
        if (segment.sn == segment.updateUna) {
//            System.out.println("sn 等于 updateUna 有bug");//不是bug
//            rmt_una = segment.sn + 1;//这个有问题...需要指向第一个未接收的,这个不是
        }

        //计算能写出多少
        int canUse = needLen - OVERHEAD;

        int canWriteAckMaskLen = 0;
        if (rcv_ackmask.length > 0) {
            //提前减去尺寸，最大 字节  如果填充payload后 还有余。则尽可能填充ackmask
            statsAckmaskMaxLen = Math.max(statsAckmaskMaxLen, rcv_ackmask.length);
            canWriteAckMaskLen = Math.min(rcv_ackmask.length, ackMaskMaxLen);
            canUse -= canWriteAckMaskLen + 2;
        }


        //合并 dr  好像没必要 压根没进去，也许在乱序传输 和丢包的情况下才有用？
        //写这个方法的目的是 合并连续的范围，但是好像多余了。
/*        snd_dr.merge((a, b) -> {
            if(a.type==DataRange.ACK||b.type==DataRange.ACK){
                return null;
            }
            if(a.type==DataRange.UNACK){
                //如果未超时
                if(a.ts>(System.currentTimeMillis()-a.rto) && a.type==DataRange.UNACK){//没到时间的  未确认跳过...(这个方法写的有点怪 混合一起但是 过于交叉)
                    //emmm
                   return null;
                }
            }
            if(b.type==DataRange.UNACK){
                //如果未超时
                if(b.ts>(System.currentTimeMillis()-b.rto) && b.type==DataRange.UNACK){//没到时间的  未确认跳过...(这个方法写的有点怪 混合一起但是 过于交叉)
                    //emmm
                    return null;
                }
            }
            //连续的 都是需要重发的就合并
            if (DataRange.isOverlap(a.start, a.length, b.start, b.length) ) {
                System.out.println("合并");
                //合并
                int newstart = Math.min(a.start, b.start);
                a.length = Math.max(a.start + a.length, b.start + b.length) - newstart;
                a.start = newstart;
//                a.rto=Math.max(a.rto,b.rto);
//                a.ts=Math.max(a.ts,b.ts);
                a.rto = b.rto;
                a.ts = b.ts;
                return a;
            }
            return null;
        });*/
        //限制在 snd_offset 开始 rcv_buf_size大小 范围内的数据
        int bufLimit = _itimediff(rmt_rcv_offset + rcv_buf_size, snd_offset);
//        int bufLimit = rcv_buf_size;
        if (bufLimit < 0) {
            System.out.println("????不可能进这里吧 应该");
            //怎么进这里了...好像是lcp没有取出数据?
            //rmt_rcv_offset落后
            //奇怪之前没进过这里 不知道改了什么和有什么影响.貌似正常运行,难道是缓冲区满了.但是rmt_rcv_offset是落后的啊
            //一般应该是 最小是0 表示满了.不会超发
        }


        boolean needSend = true;

//        if (getUnackSegCount() < wnd) {
//            needSend = true;
//        } else {
//            needSend = false;
//        }

        if (needSend) {
            {
                //1+4+2
                //需要确保不超snd_buf_size
                if (canUse > (1 + 4 + 2)) {
                    //取出数据范围
                    DR.Range pre = null;
                    canUse -= (1 + 4 + 2);
                    DR.Range range = snd_dr.needOneUseCache(canUse, bufLimit, DR.UNACK, DR.UNSEND, curTs);//一个一个取出  因为可能是多个片段
                    if ("l2".equals(name) && range != null) {
                        if (range.length + range.start + snd_offset > 4096) {
                            System.out.println("为什么超发了");
                            range = snd_dr.needOne(canUse, bufLimit, DR.UNACK, DR.UNSEND, curTs);//一个一个取出  因为可能是多个片段
                        }
                    }
                    if (range == null) {
                        //没获取到
                        //如果是忽略空包  在间隔时间内 不发送 直接返回0

                        if (ignoreEmptyPacketInterval == false && ((preDataSn - 1 == segment.sn) == false)) {//无数据发送时,间隔指定时间 发送一次空包

                            if (curTs - preSendTime < emptyPackInterval) {
                                long diff = curTs - preSendTime;
                                if (ignoreCount < maxIgnoreCount) {
                                    if ("lcp".equals(name)) {
                                        System.out.println("....lcp等待 " + (preDataSn - 1 == segment.sn));
                                    }
                                    log("空包 在间隔内忽略");
                                    range = snd_dr.needOne(canUse, bufLimit, DR.UNACK, DR.UNSEND, curTs);//一个一个取出  因为可能是多个片段
                                    return 0;
                                } else {
                                    log("忽略空包间隔 ignoreCount 大于大于阈值" + ignoreCount);
                                    ignoreCount = 0;
                                }
                            }

                        }
                    } else {
//                         System.out.println("发送" + range.start + " " + range.length+" 实际偏移"+(snd_offset+range.start));
//                        log("发送" + range.start + " " + range.length+" 实际偏移"+(snd_offset+range.start));

                        if (range.sendcount != 0) {
//                            System.out.println("之前被发送数量" + range.sendcount + " 当前包sn" + segment.sn);
//                            System.out.println(range);
                        }
                        pre = range;
                        {
//                            ByteBuf buffer = byteBufAllocator.ioBuffer(range.length);
////                            ByteBuf buffer = Unpooled.buffer();
//                            if (sndBufWriteToBuf(buffer, range.start + snd_offset, range.length) == 0) {
//                                System.out.println("为什么失败了");
//                            } else {
                            segment.payloads_stream.add(new Payload(range.start + snd_offset, range.length, null));
//                                segment.payloads_stream.add(new Payload(range.start + snd_offset, range.length, buffer));

                            canUse -= (range.length);
                            //标记 状态 和 超时时间
                            range.type = DR.UNACK;
                            range.ts = curTs;
                            range.rto = rto;
                            range.sendcount++;
//                            }
                        }

                        //后续的
                        while (canUse > (2 + 2)) {
                            canUse -= (2 + 2);
                            range = snd_dr.needOneUseCache(canUse, bufLimit, DR.UNACK, DR.UNSEND, curTs);//一个一个取出  因为可能是多个片段
                            if (range == null) {//没获取到
                                canUse += (2 + 2);
                                break;
                            }

                            if (range.sendcount != 0) {
//                                System.out.println("...之前被发送数量" + range.sendcount);
                                if (range.sendcount > 100) {
                                    System.out.println("bug!!!!!!!!!!!!!!!!!?");
                                }
//                                System.out.println(range);
                            }

                            if (range.start <= pre.start) {
                                canUse += (2 + 2);//需要有序 不然 偏移会错误  因为后续的是2字节 只能正数。且 不能距离上一个太远。
                                //这里应该不会进，除非倒霉 刚好 错了1毫秒  下次循环的时候取出来之前没取出的数据 循环时刚刚超时的数据
                                //还是暂且加上吧
                                break;
                            }
                            if (range.start + range.length - pre.start > Short.MAX_VALUE) {
                                System.out.println("bug..超出 payload 跨度过大");
                                canUse += (2 + 2);
                                break;
                            }
                            pre = range;
//                            ByteBuf buffer = byteBufAllocator.ioBuffer(range.length);
//                            ByteBuf buffer = Unpooled.buffer();

//                            if (sndBufWriteToBuf(buffer, range.start + snd_offset, range.length) == 0) {
//                                System.out.println("为什么失败了");
//                            } else {
//                                segment.payloads_stream.add(new Payload(range.start + snd_offset, range.length, buffer));
                            segment.payloads_stream.add(new Payload(range.start + snd_offset, range.length, null));

                            canUse -= (range.length);
                            if (canUse < 0) {
                                System.out.println("debug");
                                range = snd_dr.needOne(canUse - (2 + 2), bufLimit, DR.UNACK, DR.UNSEND, curTs);//一个
                            }
                            //标记 状态 和 超时时间
                            range.type = DR.UNACK;
                            range.ts = curTs;
                            range.rto = rto;
                            range.sendcount++;
//                            }

                        }
                    }
                }

            }
        }


        if (segment.payloads_stream.size() > 0) {
            segment.cmd |= CMD_PACKET_STREAM;
            preDataSn = segment.sn;
            segment.ts = curTs;


            snd_segments.add(segment);
            if (check) {
                snd_cache.put(segment.sn, segment);
            }
            //下次立即发送 为了更新una
            ignoreCount = maxIgnoreCount;
        } else {
            //空的不需要添加

        }
//        if ("lcp".equals(name)) {
//            if (segment.payloads_stream.size() == 0) {
//                List<DataRange.Range> ranges = snd_dr.needRanges(rcv_buf_size, false, DataRange.UNACK, DataRange.UNSEND);
//                int total = 0;
//                for (DataRange.Range range : ranges) {
//                    total += range.length;
//                }
//                if (total != rcv_buf_size) {
//                    //暂停看看 为什么 没开启ackmask 但是 出现了状况
//                    System.out.println("???????....");
//                }
//                log("无数据发送,等待确认的数据数量" + total);
//            }
//        }
        if (ignoreEmptyPacketInterval == false) {
            if (curTs - preSendTime < emptyPackInterval) {
                if (segment.payloads_stream.size() == 0) {
//                    DataRange.Range range = snd_dr.needOne(canUse, bufLimit, DataRange.UNACK, DataRange.UNSEND, curTs);//一个一个取出  因为可能是多个片段
                    return 0;
                }
            }
        }


        if (canUse < 0) {
            System.out.println("???? canUse<0");
        }
        boolean canCompress = false;

        if (rcv_ackmask.length > 8) {//太短的没必要压缩
            //判断能不能压缩
            byte[] compress = SnappyUtil.compress(rcv_ackmask);
            if (compress.length < rcv_ackmask.length) {//有效压缩
                //看看能不能全部写入
                if (canUse > compress.length) {//能
                    segment.ackmask=compress;
                    canUse+=(canWriteAckMaskLen-compress.length);
                    segment.cmd|=CMD_PACKET_ACKMASK_COMPRESS;
                    segment.cmd |= CMD_PACKET_ACKMASK;
                    canCompress=true;

                }

            }
        }

        if (canCompress==false) {
            if (canWriteAckMaskLen > 0) {
                //计算能写入多少
                int add = Math.max(Math.min(canUse, rcv_ackmask.length - canWriteAckMaskLen), 0);
//            add = Math.min(8, add);//单字节表示长度 最大256字节的ackmask(X 尽可能填满)

                if (add < 0) {
                    System.out.println("不可能进这里吧，以防万一怕马虎  加了断点");//....还真进了。。。发现了canUse 为负数的情况。 说明上面填充Payload逻辑有问题。先加上max0 确保不会负数
                    //canUse负数的原因是 needOne 需要的长度 传入了-1 导致变成了无限制长度。取出了超过canUse的数据。 加上判断canUse-1来跳出
                    //不对 不需要判断，是参数写错了，前面已经-(2+2)了 需要的长度应该传入 canUse 而不是再减 (2+2)
                }
                canWriteAckMaskLen += add;
                canUse -= add;
                if (add > 0) {
//                System.out.println("成功携带更多ackmask");
                }
                segment.ackmask = copyBytes(rcv_ackmask, canWriteAckMaskLen);//避免过大 导致lcp snd_offset计算超过l2 rcv_offset
                segment.cmd |= CMD_PACKET_ACKMASK;
                if ((rcv_ackmask[0] & 1) == 1) {
                    System.out.println("????bug  发送时 第一bit 必定0 ,不为0 说明una应该滑动但是没滑动，应该都修复完了");
                }


            }


        }


//        if (segment.ackmask != null) {
//            for (int i = 0; i < segment.ackmask.length; i++) {
//                boolean a = BitArrUtils.getBitInByteArray(i, segment.ackmask);
//                boolean b = rcvAckmaskSnIsAck(segment.una + i);
//                if (a == b || (a && b)) {
//
//                } else {
//                    System.out.println("ackmask 有问题");
//                    rcvAckmaskSnIsAck(segment.una + i);
//                }
//            }
//        }

//        log("send sn"+segment.sn+".......rcv_offset"+segment.rcv_offset);

        preSendTime = curTs;
        snd_next_sn++;
        int beforePos = out.writerIndex();
        segment.writeToBuf(out, this);
        //设计有问题 发送后segments也存了一份数据  snd_buf本来就存了.
        //封包后 删掉

        //记录payload 最大的数据末端位置
        for (int i = 0; i < segment.payloads_stream.size(); i++) {
            Payload cur = segment.payloads_stream.get(i);
            if (i == 0) {
                segment.payloadLastOffset = cur.offset + cur.length - 1;
            } else {
                segment.payloadLastOffset = _maxint_(cur.offset + cur.length - 1, segment.payloadLastOffset);
            }
            if (cur.data != null) {
                cur.data.release();
                cur.data = null;
            }
        }


        sendCount++;
        return out.writerIndex() - beforePos;
    }

    int rmt_real_rcv_offset;
    int rmt_rcv_offset;
    int real_rcv_offset;//限制不发送超过缓冲区用
    /**
     * 确认una 和ackmask  滑动snd 和 跳过次数  放到一个方法里 减少循环开销
     */
    private void handleAll(int rmt_una,byte[] bitarry,int rmt_offset,Segment segment){
        if(bitarry!=null){
            int last=AckMaskUtils.getRightLastOneBitIndex(bitarry);
            if(last!=-1){
                last+=rmt_una;//得到bitarry中最后一个 1的位置 计算 sn
                rmt_max_acksn=_maxint_(last,rmt_max_acksn);
            }
        }

        for (int i = 0; i < snd_segments.size(); i++) {
            Segment cur = snd_segments.get(i);
            int diff = _itimediff(rmt_una, cur.sn);
            //una和 ackmask部分
            if (diff > 0) {
                ackSeg(cur);//UNA之前的进行确认
            } else {//una 及以后的
                if (bitarry != null && -diff < bitarry.length * 8) {
                    if (AckMaskUtils.getBitInByteArray(-diff, bitarry)) {
                        ackSeg(cur);//ackmask标记为true的进行确认
                    }
                }
            }
            //ackSndWithRmtRcvOffset部分  根据rmt_offset判断是seg否已接收
            if ( (_itimediff(rmt_offset, cur.payloadLastOffset) > 0)) {
                ackSeg(cur);
            }
            //被跳过的增长  触发快速重传
            if (cur.done == false&&_itimediff(rmt_max_acksn,cur.sn)>0) {
                cur.skipcount++;
                if (cur.skipcount >= resend) {
                    if (cur.resend) {
                        continue;//已标记为重发的 忽略
                    }
                    cur.resend = true;
                    //触发快速重传
                    //标记取消了RTO 标记为UNSEND 等待下次传输
                    for (Payload payload : cur.payloads_stream) {
                        //只重传 未接收的部分
                        if (isAcked(payload.offset, payload.length)) {
                            System.out.println("快速重传只传未接收部分");
                            continue;
                        } else {
                            //snd_dr 的位置
                            int start = _itimediff(payload.offset, snd_offset);
                            int len = payload.length;
                            if (rmt != null) {
                                if (rmt.isRecived(payload.offset, payload.length) == false) {
                                    //只重传未接收到的//搞错了 无论true false 都不代表bug
                                } else {
                                    System.out.println("buggggggg");//进这里绝对有bug....应该不是 只在数据即将收完出现 而且是丢包后出现  应该是lcp没数据要发了,l2知道了更新了una,lcp认为这个包被确认了.但是其实丢了...不对是有bug啊???
                                    recvCheck();
                                    rmt.recvCheck();
                                    //已被接收了 但是丢包和延迟问题还没知道. 不是bug
                                    Assert.assertTrue(rmt.isRecived(payload.offset, payload.length));
                                }
                            }
                            //标记为UNSENED 是为了takesend能立即取出  而不是等待超时取出
                            snd_dr.markRangen(start, len, DR.UNSEND);
                            recvCheck();
                            if (rmt != null) {
                                rmt.recvCheck();

                            }
                        }
                    }


                }
            }
            if(i==0&&cur.done){
                //头部被确认的可以删除了
                snd_segments.remove(0);
                i--;
            }
 //           if(_itimediff(rmt_max_acksn,cur.sn)<0){
 //               return;
 //           }有bug，不管了 屏蔽了。上次加上打包后运行 一开始和偶尔总是重发，好像是因为这个提前跳出循环所致。先正常运行有空在看哪里算错了。
        }

    }

    public void input(ByteBuf buf){
        inputCount++;
        //更新rmt_una

        int startPos = buf.readerIndex();
        Segment segment = Segment.readFromBuf(buf);
        if (segment == null) {
            System.out.println("读取segment失败");
            buf.readerIndex(startPos);
            return;
        }
        if (snd_segments.size() > 0) {
            log("snd_segments大小" + snd_segments.size());
        }
        if (_itimediff(segment.sn, rcv_next_sn) > 200000) {
            throw new RuntimeException("收到了过大的sn" + segment.sn + "," + rcv_next_sn);
        }

        if (segment.ackmask != null) {
            if((segment.cmd&CMD_PACKET_ACKMASK_COMPRESS)!=0){
                int beforeLen=segment.ackmask.length;
                segment.ackmask=SnappyUtil.unCompress(segment.ackmask);
                if(segment.ackmask!=null){

                    System.out.println("解压成功"+segment.ackmask.length+"/"+beforeLen);
                }else{
                    System.out.println("解压失败");
                    buf.readerIndex(startPos);
                    return;
                }

            }
            System.out.println("有ackmask");
            System.out.println("收到ackmask" + segment.ackmask.length + "  seg.una" + segment.una + " " + Arrays.toString(segment.ackmask));
        }
        //处理Payload 写入rcv_buf
        //在实际取出数据的时候 进行滑动
        for (Payload payload : segment.payloads_stream) {
            rcvBufWrite(payload);
        }
        //更新 rmt_rcv_offset
        rmt_rcv_offset = _maxint_(rmt_rcv_offset, segment.rcv_offset);
        //如果是下一个要收的 则una自增 否则标记为已收到
        if (segment.sn == rcv_next_sn) {
            if (rcv_ackmask.length == 0) {
                rcv_next_sn++;
            }
        } else {
            rcvAckmaskMark(segment.sn);
        }

        handleAll(segment.una,segment.ackmask,rmt_rcv_offset ,segment);
        //更新rmt_una
        if(snd_segments.size()>0){
            this.rmt_una=_maxint_(this.rmt_una,snd_segments.get(0).sn);
        }else{
            this.rmt_una=this.snd_next_sn;
        }
        //根据updateUna 更新una
        {
//            更新una
            int diff = _itimediff(segment.updateUna, rcv_next_sn);
            if (diff > 0) {
                Assert.assertEquals(_maxint_(segment.updateUna, rcv_next_sn), rcv_next_sn + diff);
                log("根据updateUna 更新una");
                autoSlideAckMask(diff);
            }else{
                autoSlideAckMask(0);
            }
        }

//        //如果是下一个要收的 最少滑动距离1
//        if (segment.sn == rcv_next_sn) {
///*          rcv_next_sn++;
//            slideAckMask(1);//slideAckMask直接滑动  必须 对应rcv_next_sn的更改   autoSlideAckMask 内部会自动滑动 不需要专门对应
//            autoSlideAckMask();*/
//            autoSlideAckMask(1);//会更新rcv_next_sn
//        }


        //计算本机的 real_rcv_offset
        real_rcv_offset = rcv_offset + rcv_dr.leftCanShiftDistance(DR.ACK);
        //计算 远程的real_rcv_offset
//        int canDistance = snd_dr.leftCanShiftDistance(DataRange.ACK);
        int slideDistance = snd_dr.leftAutoShift(DR.ACK);
        int beforeRmtRealRcvOffset = rmt_real_rcv_offset;
        rmt_real_rcv_offset = _maxint_(rmt_real_rcv_offset, slideDistance + snd_offset);
        rmt_real_rcv_offset = _maxint_(rmt_real_rcv_offset, segment.rcv_offset);

        snd_buf.readerIndex(slideDistance);
        snd_buf.discardReadBytes();
        snd_offset+=slideDistance;
    }
    public void inputBak(ByteBuf buf) {
//        log("收到数据 size" + buf.readableBytes());
        inputCount++;
        recvCheck();

        int startPos = buf.readerIndex();
        Segment segment = Segment.readFromBuf(buf);
        if (segment == null) {
            System.out.println("读取segment失败");
            return;
        }
        if (snd_segments.size() > 0) {
            log("snd_segments大小" + snd_segments.size());
        }
        if (_itimediff(segment.sn, rcv_next_sn) > 200000) {
            throw new RuntimeException("收到了过大的sn" + segment.sn + "," + rcv_next_sn);
        }
        buf.readerIndex(startPos);
        byte[] tmp = new byte[buf.readableBytes()];
        buf.readBytes(tmp);

        log("收到数据sn" + segment.sn + "  updateuna" + segment.updateUna);

        if (segment.ackmask != null) {
            if((segment.cmd&CMD_PACKET_ACKMASK_COMPRESS)!=0){
                int beforeLen=segment.ackmask.length;
                segment.ackmask=SnappyUtil.unCompress(segment.ackmask);
                if(segment.ackmask!=null){

                    System.out.println("解压成功"+segment.ackmask.length+"/"+beforeLen);
                }else{
                    System.out.println("解压失败");
                    return;
                }

            }
            System.out.println("有ackmask");
            System.out.println("收到ackmask" + segment.ackmask.length + "  seg.una" + segment.una + " " + Arrays.toString(segment.ackmask));
        }
        if ("scp2".equals(name)) {
            boolean b = true;
        }
        if ("scp1".equals(name)) {
            boolean b = true;
        }
        //处理Payload 写入rcv_buf
        //在实际取出数据的时候 进行滑动
        for (Payload payload : segment.payloads_stream) {
            if (rmt != null) {
                if (payload.offset == rmt.snd_offset) {
                    boolean b = true;
                }
            }

            rcvBufWrite(payload);
//            if (isRecived(payload.offset, payload.length)) {
//
//            } else {
//                System.out.println("测试失败");
//                isRecived(payload.offset, payload.length);
//            }
        }
        //在尝试写入数据之后 返回.//不知道哪里有问题.导致udp模拟 居然超过10000跨度了
        if (_itimediff(rcv_next_sn, segment.sn) > 0) {
            log("忽略过时的sn" + segment.sn + "  cur" + rcv_next_sn);
            return;
        }


        //写入数据  更新real_rcv_offset

        //根据seg.updateUna 更新una(与本地取最大值)
        //先确认 sn和ackmask
        //然后计算real_rcv_offset(也会更新una)
        //根据real_rcv_offset 再确认过去的sn

        //更新 rmt_rcv_offset
        rmt_rcv_offset = _maxint_(rmt_rcv_offset, segment.rcv_offset);

        if (segment.sn == rcv_next_sn) {
            if (rcv_ackmask.length == 0) {
                rcv_next_sn++;
            }
        } else {
            rcvAckmaskMark(segment.sn);
        }
        //标记已收到

        ackSndWithAckMask(segment.una, segment.ackmask);
        //计算 real_rcv_offset
        int canDistance = snd_dr.leftCanShiftDistance(DR.ACK);
        int beforeRmtRealRcvOffset = rmt_real_rcv_offset;
        rmt_real_rcv_offset = _maxint_(rmt_real_rcv_offset, canDistance + snd_offset);
        rmt_real_rcv_offset = _maxint_(rmt_real_rcv_offset, segment.rcv_offset);
        if (rmt != null) {
            if (rmt_real_rcv_offset > rmt.real_rcv_offset) {
                System.out.println("bug.....");
                List<DR.Range> ranges = snd_dr.needRanges(canDistance, DR.ACK);
                for (DR.Range range : ranges) {
                    if (rmt.isRecived(range.start + snd_offset, range.length) == false) {
                        System.out.println("为什么不一致");//bug好像是  una的问题
                    }
                }
            }
        }
        ackSndWithRmtRcvOffset(rmt_real_rcv_offset);//发送方 根据被接收的位置 滑动


        //如果是下一个要收的 最少滑动距离1
        if (segment.sn == rcv_next_sn) {
/*          rcv_next_sn++;
            slideAckMask(1);//slideAckMask直接滑动  必须 对应rcv_next_sn的更改   autoSlideAckMask 内部会自动滑动 不需要专门对应
            autoSlideAckMask();*/
            autoSlideAckMask(1);//会更新rcv_next_sn
        }

        if (segment.payloads_stream.size() > 0) {//有数据 下次takeSend 不受间隔限制(间隔只限制无数据发送时 的封包间隔)
            ignoreCount = maxIgnoreCount;//直接设置成最大.下次立即发送.
        }


//        log("mask" + rcv_ackmask.length + "  una" + rcv_next_sn + " " + Arrays.toString(rcv_ackmask));
        //根据updateUna 更新una
        {
//            更新una
            int diff = _itimediff(segment.updateUna, rcv_next_sn);
            if (diff > 0) {
                int before = rcv_next_sn;
                byte[] beforeMask = rcv_ackmask;

//                rcv_next_sn=    _maxint_(segment.updateUna,rcv_next_sn) ;
                Assert.assertEquals(_maxint_(segment.updateUna, rcv_next_sn), rcv_next_sn + diff);
//                rcv_next_sn += diff;
//                slideAckMask(diff);//滑动
//                autoSlideAckMask();//修正
                log("根据updateUna 更新una");
                autoSlideAckMask(diff);
                if (rcv_ackmask.length > 0) {
                    if ((rcv_ackmask[0] & 1) == 1) {
                        System.out.println("找到线索");
                        rcv_next_sn = before;
                        rcv_ackmask = beforeMask;
//                        rcv_next_sn = segment.updateUna;
//                        slideAckMask(diff);//滑动

                        autoSlideAckMask(diff);
                    }
                }
            }

        }

        //计算本机的 real_rcv_offset
        real_rcv_offset = rcv_offset + rcv_dr.leftCanShiftDistance(DR.ACK);

        if (snd_segments.size() != 0) {
            //处理snd_sgements 已被确认的删除 并滑动snd_buf
            slideSnd();
            //更新跳过次数  搜索最大的已确认sn  将之前所有未接收的 跳过次数+1
            skipCountIncrement();//放到slideSnd后面  因为这个会将被跳过次数的重发(已经重发的 如果在slideSnd之前 会重新标记为unsend 但是实际已经收到了  所以必须放到slideSnd后面 )

        }

    }


    private void ackSndWithAckMask(int rmt_una, byte[] bitarry) {
        //更新远方真实 rcv_offset
        //所有小于una的都进行确认
        for (int i = 0; i < snd_segments.size(); i++) {
            Segment cur = snd_segments.get(i);
            int diff = _itimediff(rmt_una, cur.sn);
            if (diff > 0) {
//                ackSn(cur.sn);
                ackSeg(cur);
            } else {//una 及以后的

                if (bitarry != null && -diff < bitarry.length * 8) {
                    if (AckMaskUtils.getBitInByteArray(-diff, bitarry)) {

                        ackSeg(cur);
                    }
                } else {
                    break;
                }

            }
        }
        if (bitarry == null) {
            return;
        }
//        System.out.println("=================处理ackmask");
//        for (int i = 0; i < bitarry.length * 8; i++) {
//            if (BitArrUtils.getBitInByteArray(i, bitarry)) {
//                log("确认askmask sn" + (rmt_una + i));
//                ackSn(rmt_una + i);
//                if (rmt != null) {
//                    if (rmt.rcvAckmaskSnIsAck(rmt_una + i) == false) {
//                        System.out.println("有问题");
//                    }
//                }
//
//            }
//        }
    }


    /**
     * 根据远程 rcv_offset滑动
     */
    private void ackSndWithRmtRcvOffset(int rmt_offset) {

        boolean fail = false;
        //如果seg 所有数据 均小于 rmt_offset 则标记为已接收
        label:
        for (int i = 0; i < snd_segments.size(); i++) {
            Segment cur = snd_segments.get(i);
            if (cur.done) {
                continue;
            }
            if (_itimediff(rmt_offset, cur.payloadLastOffset) > 0) {
                //ackSn(cur.sn);
				ackSeg(cur);//哈
            } else {
//                break;//因为重发的 分配新的sn
//                偏移不是有序的
//                所以只能遍历所有。

            }

        }

    }


    private void skipCountIncrement() {
        //小于rmt_una的全部标记跳过次数
        for (int i = 0; i < snd_segments.size(); i++) {
            Segment cur = snd_segments.get(i);
            if (_itimediff(rmt_max_acksn, cur.sn) > 0) {
                //过去的
                if (cur.done == false) {
                    cur.skipcount++;
                    if (cur.skipcount >= resend) {
                        if (cur.resend) {
                            continue;//已标记为重发的 忽略
                        }
                        cur.resend = true;

                        //触发快速重传
                        //标记取消了RTO 标记为UNSEND 等待下次传输

                        for (Payload payload : cur.payloads_stream) {
                            //只重传 未接收的部分
                            if (isAcked(payload.offset, payload.length)) {
                                System.out.println("快速重传只传未接收部分");
                                continue;
                            } else {
                                //snd_dr 的位置
                                int start = _itimediff(payload.offset, snd_offset);
                                int len = payload.length;
//                                System.out.println("---标记2——" + start + "_" + len + "  snd_offset" + snd_offset+"  sn:"+cur.sn);
//                                if(i==0){
//                                    if(start!=0){
//                                        System.out.println("发现问题");
//                                    }
//                                }
//                                if (_itimediff(_itimediff(payload.offset, snd_offset) + payload.length, rmt_real_rcv_offset) > 0) {
//                                    System.out.println("发现超过rcv_offset的bug3  rmt_real_rcv_offset"+rmt_real_rcv_offset);//忘了为什么要这么写了要检查什么忘了  好像有问题,这个是错误的 不能用
//                                }else{
//                                    allRecv=false;
//                                }

                                if (rmt != null) {
                                    if (rmt.isRecived(payload.offset, payload.length) == false) {
                                        //只重传未接收到的//搞错了 无论true false 都不代表bug
                                    } else {
                                        System.out.println("buggggggg");//进这里绝对有bug....应该不是 只在数据即将收完出现 而且是丢包后出现  应该是lcp没数据要发了,l2知道了更新了una,lcp认为这个包被确认了.但是其实丢了...不对是有bug啊???
                                        recvCheck();
                                        rmt.recvCheck();
                                        //已被接收了 但是丢包和延迟问题还没知道. 不是bug
                                        Assert.assertTrue(rmt.isRecived(payload.offset, payload.length));
                                    }
                                }

                                //标记为UNSENED 是为了takesend能立即取出  而不是等待超时取出
                                snd_dr.markRangen(start, len, DR.UNSEND);

//                                if (snd_dr.rangeCheck(start, len, DataRange.UNSEND) == false) {
//                                    System.out.println("发现停滞原因");
//                                }

                                recvCheck();
                                if (rmt != null) {
                                    rmt.recvCheck();

                                }
                            }
                        }


                    }
                }

            } else {
                break;
            }

        }
 /*       boolean find = false;

        for (int i = snd_segments.size() - 1; i >= 0; i--) {
            //找到第一个 接收的  之前的全部 skipCount++
            //如果 跳过次数 超过 快速重传要求的次数 标记为未发送 让之后可以被立即发送
            Segment cur = snd_segments.get(i);
            if (find) {//找到了 前面的未确认的 全部 跳过次数+1 并标记
                if (cur.done == false) {
                    cur.skipcount++;
                    if (cur.skipcount >= resend) {
                        System.out.println("触发快速重传 被跳过次数" + cur.skipcount + "sn" + cur.sn);
                        if (cur.sendCount != 0) {
                            //如果在 100毫秒内 不再重发
                            if(System.currentTimeMillis()-cur.ts>100){
                                System.out.println("重发超过100毫秒的");
                            }else {
                                System.out.println("忽略已经标记为重发的");
                                continue;
                            }
                        }
                        cur.sendCount++;
                        //触发快速重传
                        //标记取消了RTO 标记为UNSEND 等待下次传输
                        for (Payload payload : cur.payloads_stream) {
                            //只重传 未接收的部分
                            if (isAcked(payload.offset, payload.length)) {
                                System.out.println("快速重传只传未接收");
                                continue;
                            } else {
                                System.out.println("---标记2——" + _itimediff(payload.offset, snd_offset) + "_" + payload.length);
                                if (_itimediff(_itimediff(payload.offset, snd_offset) + payload.length, rmt_real_rcv_offset) > 0) {
                                    System.out.println("发现超过rcv_offset的bug");
                                }
                                if (rmt != null) {
                                    if (rmt.isRecived(payload.offset, payload.length) == false) {
                                        //只重传未接收到的//搞错了 无论true false 都不代表bug
                                    } else {
                                        System.out.println("buggggggg");//进这里绝对有bug....应该不是 只在数据即将收完出现 而且是丢包后出现  应该是lcp没数据要发了,l2知道了更新了una,lcp认为这个包被确认了.但是其实丢了...不对是有bug啊???
                                        recvCheck();
                                        rmt.recvCheck();
                                        rmt.isRecived(payload.offset, payload.length);
                                    }
                                }

                                //标记为UNSENED 是为了takesend能立即取出  而不是等待超时取出
                                snd_dr.markRange(_itimediff(payload.offset, snd_offset), payload.length, DataRange.UNSENED);
                                recvCheck();
                                if (rmt != null) {
                                    rmt.recvCheck();

                                }
                            }
                        }

                    }
                }

            } else {
                if (cur.done == true) {
                    find = true;
                    maxSn = cur.sn;

                    Assert.assertTrue(_itimediff(rmt_max_acksn,cur.sn)>=0);
                }
            }

        }
*/
    }

    /**
     * 根据done标记滑动.更新snd_offset
     */
    private void slideSnd() {

        long st = System.currentTimeMillis();
        //snd不保留  done的 因为没必要
        //重发的时候  从snd_segments 找可重发的(fastack  timeout)
        int distance = 0;
        boolean add = true;
        int maxPos = 0;
        int offsetAdd = snd_offset;//跟 dr滑动 对比是否一致。。应该是一致的 不一致可能哪里写错了
//        System.out.println("slideSnd耗时-......"+(System.currentTimeMillis()-st));
//        int canShiftDistance = snd_dr.leftCanShiftDistance(DataRange.ACK);


        int slideDistance = snd_dr.leftAutoShift(DR.ACK);
//        System.out.println("slideSnd耗时0......"+(System.currentTimeMillis()-st));
        snd_buf.readerIndex(slideDistance);
        snd_buf.discardReadBytes();

//        System.out.println("slideSnd耗时1......"+(System.currentTimeMillis()-st));
//        List<Segment> pre = new ArrayList<>();
//        pre.addAll(snd_segments);
        label:
        for (int i = 0; i < snd_segments.size(); i++) {
            Segment cur = snd_segments.get(i);
            if (cur.done == false) {
                add = false;
            }

            if (add) {
                //检查是否确认
                if (cur.sn + 1 < rmt_una) {
                    System.out.println("需要取最大值");
                }
                int before = rmt_una;

                rmt_una = cur.sn + 1;
                log("更新远程una" + rmt_una + " slideSnd");
                Assert.assertTrue(_itimediff(before, rmt_una)<0);
                distance++;
                //更新snd_offset
                for (Payload payload : cur.payloads_stream) {
                    int diff = _itimediff(payload.offset + payload.length, snd_offset);
                    if (diff > slideDistance) {
                        System.out.println("暂停找原因");
//                        snd_segments = pre;
//                        slideSnd();
//                        throw new RuntimeException("有bug 这个应该修复了");
                    }
                    offsetAdd = _maxint_(offsetAdd, payload.offset + payload.length);
                }
                if (_itimediff(cur.sn, rmt_una) >= 0) {//大于等于rmt_una 有bug 分片提前删除了
                    System.out.println("seg错误删除");
                }

                snd_segments.remove(i);
                cur.recycle();
                i--;
                ;
            } else {
                //已经ack的 可以删除 最后一个 保留  用来 前面缺失的收到的时候  可以正常更新snd_offset(最后一个seg的最大数据位置)
                //现在留着了,记录滑动距离(有空改成  distance加等于 间隔应该没问题 先修其他bug...)

            }
        }
//        System.out.println("slideSnd耗时2......"+(System.currentTimeMillis()-st));
        //TODO 不一致不知道为什么
//        rmt_rcv_offset+=slideDistance;
        snd_offset += slideDistance;


        if (rmt != null) {
            if (_itimediff(snd_offset, rmt.real_rcv_offset) > 0) {//如果大绝对有问题....不一定 只要最找正常  可能是 没有recv导致的rcv_offset未更新
                System.out.println(rmt.isRecived(snd_offset - slideDistance, slideDistance));
                System.out.println("发现BUG9");
            }
        }


        if (snd_offset != offsetAdd) {
            if (rmt != null) {
                System.out.println(rmt.isRecived(snd_offset - slideDistance, slideDistance));
                if (rmt.isRecived(snd_offset - slideDistance, slideDistance) == false) {
                    System.out.println("slideSnd dr滑动的是并未接收的！");//好像 +滑动距离 就行.另一个只是检查连续的 统计数量.验证用,但是为什么不匹配...
                }
            }


        }

//        //暂时采用offsetAdd因为大一些
//        snd_dr.leftShift(_itimediff(offsetAdd,snd_offset));
//        snd_offset=offsetAdd;
    }



    boolean check = false;

    //检查 snd_rcv 和snd_dr是否匹配
    private void recvCheck() {
        if (check == false) {
            return;
        }

        rcv_buf.readerIndex(0);
        {
            int readLen = rcv_dr.leftCanShiftDistance(DR.ACK);
            if (readLen > rcv_buf.writerIndex()) {
                System.out.println("bug....readLen>rcv_buf.writerIndex");
            }
        }
        for (DR.Range range : rcv_dr.ranges) {
            //如果ack了的但是 大于rcv_buf writer 绝对有问题
            if (range.type == DR.ACK) {
                if (range.start + range.length > rcv_buf.writerIndex()) {
                    System.out.println("rcv_dr 存在 确认了  位置大于rcv_bUf的" + "  " + (range.start + range.length + "  " + rcv_buf.writerIndex));
                }
            }
        }
        if (rmt != null) {
            //rcv_offset+snd_dr 必小于rmt.end_position
            int rmt_end_offset = rmt.snd_offset + rmt.snd_buf.writerIndex();
            if (rmt_end_offset < rcv_offset + rcv_dr.getSize()) {
                throw new RuntimeException("bug rmt_end_offset<rcv_offset+rcv.dr.size");
            }

            for (int i = 0; i < snd_segments.size(); i++) {
                Segment cur = snd_segments.get(i);
                if (cur.sn < rmt.rcv_next_sn) {
                    //检查是否已被接收
                    for (Payload payload : cur.payloads_stream) {
                        if (rmt.isRecived(payload.offset, payload.length) == false) {
                            rmt.isRecived(payload.offset, payload.length);
                            throw new RuntimeException("bug snd未被接收的数据 被标记为已接收");
                        }
                    }
                } else if (cur.done) {
                    for (Payload payload : cur.payloads_stream) {
                        int offset = payload.offset;
                        int len = payload.length;
                        int rcv_offset = rmt.rcv_offset;
                        if (rmt.isRecived(payload.offset, payload.length) == false) {
                            int after_rcv_offset = rmt.rcv_offset;
                            boolean r = rmt.isRecived(payload.offset, payload.length);
                            throw new RuntimeException("bug snd未被接收的数据 被标记为已接收");
                        }
                    }
                }
            }
        }


    }

    public int recv(ByteBuf recBuf) {
        recvCheck();

        if (rmt != null) {
            rmt.recvCheck();
        }
        //取出数据
        rcv_buf.readerIndex(0);
//        {
//            int readLen = rcv_dr.leftCanShiftDistance(DataRange.ACK);
//            if (readLen > rcv_buf.writerIndex()) {
//                System.out.println("bug....readLen>rcv_buf.writerIndex");
//            }
//        }
        int readLen = rcv_dr.leftAutoShift(DR.ACK);
        if (readLen < 1) {
            return 0;
        }
        //不能直接获取 范围内的被确认的范围. 因为需要连续的
//        List<DataRange.Range> ranges = rcv_dr.needRanges(rcv_buf_size, DataRange.ACK);
//        Assert.assertEquals(DataRange.sumTotalSize(ranges), rcv_buf.readableBytes());
//        List<DataRange.Range> ranges=rcv_dr.needCanShiftRanges(DataRange.ACK);
//        Assert.assertEquals(DataRange.sumTotalSize(ranges), readLen);


//        Assert.assertEquals(readLen,rcv_buf.readableBytes());
//        readLen=rcv_dr.leftAutoShift(DataRange.ACK);

        if (_itimediff(real_rcv_offset, rcv_offset + readLen) < 0) {
            System.out.println("bug" + "_itimediff(real_rcv_offset, rcv_offset + readLen) < 0");

        }


        int b = rcv_buf.readableBytes();
        try {
//            recBuf.writeBytes(rcv_buf, readLen);

            rcv_buf.readBytes(recBuf, readLen);
        } catch (Exception e) {
            rcv_buf.readBytes(recBuf, readLen);
            throw new RuntimeException(e);
        }

/*        if(readLen==rcv_buf.writerIndex()){
//            ByteBuf slice = rcv_buf.slice(readLen,rcv_buf.writerIndex());
//            rcv_buf.readerIndex(0);
//            rcv_buf.writerIndex(0);
//            rcv_buf.writeBytes(slice);//性能很差  使用slice 还不如discard


//            rcv_buf.discardSomeReadBytes();
//            rcv_buf.clear();
//            rcv_buf.discardReadBytes();//滑动  好像不需滑动,重置读写即可//必须使用discard

//            if(rcv_buf.capacity()<rcv_buf_size){
////                rcv_buf.addComponent(Unpooled.wrappedBuffer(new byte[Math.max(readLen,1024*4)]));
////                rcv_buf.writeBytes(new byte[Math.max(readLen,1024*16)]);
//                rcv_buf.capacity(rcv_buf_size);
//            }
//rcv_buf= (CompositeByteBuf) rcv_buf.copy(readLen,rcv_buf.writerIndex()-readLen);
        }else{
            rcv_buf.discardReadBytes();//滑动  好像不需滑动,重置读写即可//必须使用discard
            if(rcv_buf.capacity()<rcv_buf_size){
                rcv_buf.addComponent(Unpooled.wrappedBuffer(new byte[Math.max(readLen,1024*16)]));
            }
        }*/

//        rcv_buf.clear();


        //使用 封装的 循环Buf
        rcv_buf.discardReadBytes();

//        long st=System.currentTimeMillis();
//        rcv_buf.discardReadBytes();//滑动  好像不需滑动,重置读写即可

//        long useTime=System.currentTimeMillis()-st;
//        if(System.currentTimeMillis()-st>20){
//            System.out.println("rcv_buf滑动耗时"+useTime);
//        }

        if (rcv_buf.readerIndex() != 0) {
            System.out.println("啊?");
        }

        rcv_offset += readLen;
        return readLen;
    }


    private void slideAckMask(int distance) {
        //滑动
        if (distance == 0) {
            return;
        }
        rcv_ackmask = AckMaskUtils.slideByteArray(distance, rcv_ackmask);
        log("ackmask 滑动距离----" + distance);
    }

    private void autoSlideAckMask() {
        int distance = AckMaskUtils.getLeftLastOneBitIndex(rcv_ackmask);
        if (distance < 0) {
            return;
        }
        rcv_next_sn += distance + 1;
        slideAckMask(distance + 1);
    }

    /**
     * 滑动最小距离.如果滑动后是连续的1 继续滑动
     *
     * @param minDistance
     */
    private void autoSlideAckMask(int minDistance) {
        if (rcv_ackmask.length == 0) {
            rcv_next_sn += minDistance;
            return;
        }

        int distance = AckMaskUtils.getLeftLastOneBitIndex(rcv_ackmask, minDistance);//得到最后一个下标
        if (distance < 0) {
            distance = minDistance;
        } else {
            distance += 1;
        }
        if (distance < 0) {
            return;
        }

        rcv_next_sn += distance;
        log("autoSlideAckMask 更新UNA " + rcv_next_sn + " 滑动距离" + distance);
        slideAckMask(distance);
    }

    /**
     * 一个包 避免多次acksn 导致多次跳过 触发fastack 比如ackmask多次触发
     */
    private void ackSn(int sn) {
        boolean r = ackSn(sn, false);
        if (rmt != null) {
            if (sn > snd_next_sn) {
                System.out.println("bug.......sn超过实际");
            }
        }
        int diff = _itimediff(sn, rmt_una);//队列里存的是 rmt_una 及之后的 seg
        if (diff >= 0) {
            if (r == false) {
//                System.out.println("不存在 sn"+sn+" snd_next_sn"+snd_next_sn);//没有数据(不加入snd_segments)或者已经删除的  找不到
            }

        } else {
            //应该是 跨度 大幅度更新una
//            System.out.println(r);
//            System.out.println("error ackSn " + sn + "被忽略  rmt_una:" + rmt_una);
        }
    }

    public boolean isAcked(int offset, int len) {
        //        计算开始位置
        int start = _itimediff(offset, snd_offset);
        int end = _itimediff(offset + len, snd_offset);
        if (end <= 0) {
            return true;//过去的
        }
        if (start < 0) {
            start = 0;
        }
        return snd_dr.rangeCheck(start, end - start, DR.ACK);
    }

    public boolean isRecived(int offset, int len) {
//        return true;//暂时屏蔽 测试性能
//        计算开始位置
        int start = _itimediff(offset, rcv_offset);
        int end = _itimediff(offset + len, rcv_offset);
        if (end <= 0) {
            return true;//过去的
        }
        if (start < 0) {
            start = 0;
        }
        return rcv_dr.rangeCheck(start, end - start, DR.ACK);


    }

    public void checkSn(int sn) {
        //确认一个sn的时候 判断是否数据真的被接受了
        if (check) {
            if (rmt != null) {
                Segment seg = snd_cache.get(sn);
                if (seg != null) {
                    for (Payload payload : seg.payloads_stream) {

                        if (rmt.isRecived(payload.offset, payload.length) == false) {
                            System.out.println("错误!确认了并未接受的sn");
                            rmt.isRecived(payload.offset, payload.length);
                        }
                    }

                }
            }

        }
    }

    private void ackSeg(Segment cur) {
        if (cur.done) {
            //不会重复确认
//                    System.out.println("不会重复确认");
            return;//true;//这句好像是多余的 不需要加 bug跟这里没关系
        }
        cur.done = true;
        rmt_max_acksn = _maxint_(rmt_max_acksn, cur.sn);
        for (Payload payload : cur.payloads_stream) {
            int start = _itimediff(payload.offset, snd_offset);//在snd_dr snd_buf的偏移
            if (start + snd_offset + payload.length > rmt_real_rcv_offset) {
                log("bug1");//不应该超过 rcv_offset  是bug//原因疑似 input 收到了连续的数据但是没有更新 rcv_offset 因在recv时才会更新。导致虽然收到了 但是并没有滑动rmt.rcv_offset//解决方法  ackSn的时候 不进行检查了
//                        continue label;//如果是  ackMask 确认sn 和确认una之前的. rmt_real_rcv_offset
                //rmt_real_rcv_offset可能还未更新. 比如 先确认ackmask 然后 滑动 才能得到rmt_real_rcv_offset
                //这里 跳过 在之后也会确认.  好像是多余的.怎么都行.
            }
            if (start < 0) {
                //可能是收过的
                //也可能是 部分重叠的
                //计算重叠的数量
                int needLen = payload.length + start;//start是负数 相加得到 0偏移之后的数量
                if (needLen <= 0) {
                    System.out.println("忽略已接收");
                    continue;
                } else {
                    System.out.println("重叠的一部分需要接收");


//                    log("---标记3——" + 0 + "_" + (needLen));
                    if (rmt != null) {
                        if (_itimediff(snd_offset + needLen, rmt.real_rcv_offset) > 0) {//忘了为什么判断这个了.好像不代表有bug,可能代表 滞后了
                            System.out.println("发现超过rcv_offset的bug1");
                        }
                        if (rmt.isRecived(0, needLen) == false) {
                            System.out.println("出现offset错误");
                        }
                    }
                    snd_dr.markRangen(0, needLen, DR.ACK);
                    continue;
                }

            }
            if (start + payload.length > snd_dr.getSize()) {
//                        超过容量肯定有问题
                System.out.println("严重bug" + (start + payload.length));
                continue;
                //不对..start+payload.length 超过你snd_dr容量 有问题
            }
            //完全在范围的 进行标记


//            log("---标记4——" + start + "_" + (payload.length) + "--" + (snd_offset + start) + ".....payloads_size" + cur.payloads_stream.size());
            if (rmt != null) {
                if (_itimediff(start + payload.length, rmt.real_rcv_offset) > 0) {
//                            System.out.println("发现超过rcv_offset的bug2");//串行 无快速重传时 是bug,快速重传时需要比较 接收到的最大位置

                }
                if (rmt.isRecived(payload.offset, payload.length) == false) {
                    System.out.println("出现offset错误");
                }
            }
            snd_dr.markRangen(start, payload.length, DR.ACK);
        }
        long rtt = System.currentTimeMillis() - cur.ts;
        update_rtt((int) rtt);
        rto = (int) rx_rto;
//                System.out.println("rtt:"+rtt);
    }

    private boolean ackSn(int sn, boolean updateSkipCount) {
        //测试rmt是否真的收到了数据
        checkSn(sn);

        rmt_max_acksn = Math.max(rmt_max_acksn, sn);
        if (rmt != null) {
            if (sn > rmt.rcv_next_sn) {
//                System.out.println("如果没开ackmask 进入这里可能有问题？？ 跨sn也可能是丢包");
            }
        }

        label:
        for (int i = 0; i < snd_segments.size(); i++) {
            Segment cur = snd_segments.get(i);
            if (cur.sn == sn) {
                ackSeg(cur);
                return true;
            } else {
                if (updateSkipCount) {
                    if (cur.sn < sn) {//cur.sn 小于确认的sn 说明 跳过了
                        cur.skipcount++;
                    }
                }

            }
        }
        return false;
    }

    static long _ibound_(long lower, long middle, long upper) {
        return _imin_(_imax_(lower, middle), upper);
    }

    static long _imin_(long a, long b) {
        return a <= b ? a : b;
    }

    static long _imax_(long a, long b) {
        return a >= b ? a : b;
    }

    static int _maxint_(int a, int b) {
        if (_itimediff(a, b) > 0) {
            return a;
        } else {
            return b;
        }
    }

    static int _minint_(int a, int b) {
        if (_itimediff(b, a) > 0) {
            return a;
        } else {
            return b;
        }
    }

    static int _itimediff(long later, long earlier) {
        return ((int) (later - earlier));
    }

    public static class Payload {
        int offset;
        int length;
        ByteBuf data;

        public Payload() {
        }

        public Payload(int offset, int length, ByteBuf data) {
            this.offset = offset;
            this.length = length;
            this.data = data;
        }

        public Payload slice(int length) {

            if (data.readableBytes() >= length) {
                int canReadLen = Math.min(length, data.readableBytes());
                if (canReadLen == data.readableBytes()) {//可以完整取出
                    Payload p2 = new Payload();
                    p2.offset = offset;
                    p2.data = data;
                    p2.length = length;
                    data = null;//之前的用不到了。 如果null 说明整个都重发了，如果非null 说明发了一部分。 发出去的数量是 读下标的位置
                    return p2;
                } else {//不能完整取出 进行拆分
                    ByteBuf buf = Unpooled.buffer(length);
                    Payload p2 = new Payload();
                    p2.offset = data.readerIndex() + offset;//读取直接 记录 读取开始偏移
                    buf.writeBytes(data, canReadLen);//取出能取出的数据
                    p2.data = buf;
                    p2.length = buf.readableBytes();
                    return p2;
                }
            } else {
                throw new IllegalArgumentException("长度超出范围");
            }
        }

        public static void readToList(ByteBuf buf, List<Payload> list) {
            //读取包  分段数量
            int count = buf.readByte() & 0xFF;
            count++;
            Payload first = null;
            Payload pre = null;
            for (int i = 0; i < count; i++) {
                Payload payload = new Payload();
                if (i == 0) {
                    first = payload;
                    payload.offset = buf.readInt();
                } else {
                    int offset = (buf.readShort() & 0xFFFF) + pre.length + pre.offset;
                    payload.offset = offset;
                }
                int len = (buf.readShort() & 0xFFFF);
                len++;

                payload.length = len;
                payload.data = Unpooled.buffer();
                payload.data.writeBytes(buf, len);
//                buf.readBytes(payload.data, len);

                pre = payload;
                list.add(payload);
            }
        }

        //读完重置读 位置
        public static void writeToBuf(ByteBuf buf, List<Payload> list) {
            //需要确保 offset小的在前 不然偏移负数了 读取时错误
            list.sort((a, b) -> {
                return Integer.compare(a.offset, b.offset);
            });
            int count = list.size();
            if (count == 0) {
                return;
            }
            buf.writeByte(count - 1);
            Payload first = null;
            Payload pre = null;
            for (int i = 0; i < count; i++) {
                Payload payload = list.get(i);
                if (i == 0) {
                    first = payload;
//                    if (payload.offset < 0) {
//                        System.out.println("定位");//超过2G 很容易就溢出了.正常情况.一开始加这个是判断序列化反序列化是不是有错误用的
//                    }
//                    System.out.println("paylaod.offset" + payload.offset);
                    buf.writeInt(payload.offset);

                } else {
                    int offset = payload.offset - pre.offset - pre.length;
                    if (offset < 0) {//也许在缓冲区大的时候  前后跨度大超过32KB左右或64KB 就会错误 因为写出成Short 读取是读成无符号int 应该跨度达到64K就会出问题
                        System.out.println("定位");
                    }
                    buf.writeShort(offset);
                }
                if (payload.data.readableBytes() == 0) {
                    throw new RuntimeException("payload data 无可读数据");
                }
                buf.writeShort(payload.length - 1);
                payload.data.markReaderIndex();
                buf.writeBytes(payload.data);
                payload.data.resetReaderIndex();//重置读位置，方便下次读取，而且读位置 还有  未发送部分的左右
                pre = payload;

            }

        }

        public static void writeToBuf(ByteBuf buf, List<Payload> list, SCP lcp) {
            //需要确保 offset小的在前 不然偏移负数了 读取时错误
//            list.sort((a, b) -> {
//                return Integer.compare(a.offset, b.offset);
//            });//现在取出是有序的  不需要排序了而且 回绕时  一个包包含  回绕前和会绕后  根据大小进行排序会导致 顺序错误  导致无法传输
            int count = list.size();
            if (count == 0) {
                return;
            }
            buf.writeByte(count - 1);
            Payload first = null;
            Payload pre = null;
            for (int i = 0; i < count; i++) {
                Payload payload = list.get(i);
                if (i == 0) {
                    first = payload;
//                    if (payload.offset < 0) {
//                        System.out.println("定位");//超过2G 很容易就溢出了.正常情况.一开始加这个是判断序列化反序列化是不是有错误用的
//                    }
//                    System.out.println("paylaod.offset" + payload.offset);
                    buf.writeInt(payload.offset);

                } else {
                    int offset = payload.offset - pre.offset - pre.length;
                    if (offset < 0) {//也许在缓冲区大的时候  前后跨度大超过32KB左右或64KB 就会错误 因为写出成Short 读取是读成无符号int 应该跨度达到64K就会出问题
                        System.out.println("定位2");
                    }
                    buf.writeShort(offset);
                }
                if (payload.data != null && payload.data.readableBytes() == 0) {
                    throw new RuntimeException("payload data 无可读数据");
                }
                buf.writeShort(payload.length - 1);

                lcp.sndBufWriteToBuf(buf, payload.offset, payload.length);
                pre = payload;
            }
        }

        public static void main(String[] args) {
            ArrayList<Payload> list = new ArrayList<>();
            ArrayList<Payload> list2 = new ArrayList<>();
            {
                Payload payload = new Payload();
                payload.offset = 100;
                payload.length = 100;
                payload.data = Unpooled.copiedBuffer(new byte[payload.length]);
                list.add(payload);
            }
            {
                Payload payload = new Payload();
                payload.offset = 300;
                payload.length = 100;
                payload.data = Unpooled.copiedBuffer(new byte[payload.length]);
                list.add(payload);
            }
            {
                Payload payload = new Payload();
                payload.offset = 400;
                payload.length = 64 * 1024;
                payload.data = Unpooled.copiedBuffer(new byte[payload.length]);
                list.add(payload);
            }
            ByteBuf buf = Unpooled.buffer();
            writeToBuf(buf, list);
            System.out.println(buf.readableBytes());
            readToList(buf, list2);
            System.out.println(buf.readableBytes());
            System.out.println(new Segment(null).computePayloadsLength(list));


        }
    }

    public static class Segment {

        private int updateUna;//更新远端的una
        public int end_offset;
        int payloadLastOffset;//数据的末尾  避免每次遍历paylaod用的  根据offset是否过去进行确认
        private byte[] ackmask;
        private int preHavaDataSn;
        private boolean done;
        private boolean resend;
        private final Recycler.Handle<Segment> recyclerHandle;
        /**
         * 命令
         **/
        private byte cmd;

        /**
         * message分片segment的序号
         **/
        public int sn;
        /**
         * 待接收消息序号(接收滑动窗口左端)
         **/
        private int una;

        /**
         * 发送时的 超时等待重传时间
         **/
        private int rto;
        /**
         * 收到ack时计算的该分片被跳过的累计次数，即该分片后的包都被对方收到了，达到一定次数，重传当前分片
         **/
        private int skipcount;

        private long ts;
        //        private int end_position;
//        boolean isOrderPacket;
//        List<Payload> payloads_stream = new ArrayList<>();
        List<Payload> payloads_stream = new RingArrayList<>();
//        List<Payload> payloads_packet_ordered = new ArrayList<>();
//        List<Payload> payloads_packet_unordered = new ArrayList<>();
//        byte[] mask;

        Segment next;
//        boolean isStart;
//        boolean isEnd;
        /**
         * 被发送的次数，因为会将多个超时的 合到一个包里 ，所以每次发送 取被合并的包 发送次数 最大值+1
         * 能发现 有数据包 被发送的次数达到最大，但是 不能知道是哪个，也没必要。如果用来检测  发送失败 超过指定次数断开 足矣
         */
        int sendCount;//被发送的次数  如果是0 说明还没被发过

        private static final Recycler<Segment> RECYCLER = new Recycler<Segment>() {
            @Override
            protected Segment newObject(Recycler.Handle<Segment> handle) {
                return new Segment(handle);
            }
        };
        private int rcv_offset;

        private Segment(Recycler.Handle<Segment> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }


        /**
         * 只切分 不设置其他信息
         * 如果是null说明不需要拆分
         *
         * @return
         */

        void recycle() {
//            if(1+1==2){
//                return;
//            }

            ts = 0;
            cmd = 0;
            sn = 0;
            una = 0;
            rto = 0;
            payloads_stream.clear();
            sendCount = 0;
            updateUna = 0;
            end_offset = 0;
            preHavaDataSn = 0;
            rcv_offset = 0;
            skipcount = 0;
            this.rto = 0;
            this.ts = 0;
            done = false;
            ackmask = null;
            resend = false;
            skipcount = 0;
            recyclerHandle.recycle(this);
        }

        static Segment createSegment() {
            Segment seg = RECYCLER.get();
            return seg;
        }

        public int computeLength() {
            //cmd 1 sn 3 una 3
            int len = 1 + 3 + 3;
//            if ((cmd & CMD_END_POSITION) != 0) {
//                len += 4;
//            }
            if ((cmd & CMD_PACKET_STREAM) != 0) {
                len += computePayloadsLength(payloads_stream);
            }
//            if ((cmd & CMD_PACKET) != 0) {
//                len += computePayloadsLength(payloads_packet_ordered);
//                len += computePayloadsLength(payloads_packet_unordered);
//            }
            return len;
        }

        public int computePayloadsLength(List<Payload> list) {
            int len = 1;//count 1B
            for (int i = 0; i < list.size(); i++) {
                Payload cur = list.get(i);
                if (i == 0) {
                    len += 4;
                } else {
                    len += 2;
                }
                len += 2;
                len += cur.data.readableBytes();
            }
            return len;
        }

        public static Segment
        readFromBuf(ByteBuf buf) {
            if (buf.readableBytes() >= OVERHEAD) {//读取头部  判断好像没意义  如果只根据长度 读取  数据也可能是错误的。
                // 只能确保传入的数据是可靠的 但是因为没有conv的缘故 也没法校验是不是该连接 就算有conv也存在恶意篡改的可能 所以 就这样吧
                Segment seg = null;
                int cmd = buf.readByte();
                seg = Segment.createSegment();
                seg.cmd = (byte) cmd;

                seg.sn =buf.readInt();

                seg.una = buf.readInt();
                byte[] b4 = new byte[4];

                seg.rcv_offset =buf.readInt();

                seg.updateUna = buf.readInt();


                if ((cmd & CMD_PACKET_STREAM) == 0) {
                    //没有数据  附带snd_offset
//                    seg.end_offset = buf.readInt();//不需要了  多余的
                }


                if ((cmd & CMD_PACKET_ACKMASK) != 0) {
                    //读取长度
                    short len = buf.readShort();
                    if (len > 0) {
                        byte[] ackmask = new byte[len];
                        buf.readBytes(ackmask);
                        seg.ackmask = ackmask;
                    } else {
                        System.out.println("读取ackmask错误 len" + len);
                        seg.recycle();
                        return null;
                    }
                }
                if ((cmd & CMD_PACKET_STREAM) != 0) {
                    Payload.readToList(buf, seg.payloads_stream);
                }
                return seg;
            }

            return null;
        }

        public void writeToBuf(ByteBuf buf) {
            writeToBuf(buf, null);
        }

        public void writeToBuf(ByteBuf buf, SCP lcp) {
            //写出头部
            buf.writeByte(cmd);
            buf.writeInt(sn);
            buf.writeInt(una);
            buf.writeInt(rcv_offset);

            buf.writeInt(updateUna);
//            始终写入
            if ((cmd & CMD_PACKET_STREAM) == 0) {
                //没有数据  附带end_offset
//                buf.writeInt(end_offset);//不需要了多余的
            }
//            buf.writeInt(snd_offset);
            if (cmd != 0) {//有 流 写出流
                if ((cmd & CMD_PACKET_ACKMASK) != 0) {
                    buf.writeShort(ackmask.length);
                    buf.writeBytes(ackmask);
                }
                if ((cmd & CMD_PACKET_STREAM) != 0) {
                    if (lcp == null) {
                        Payload.writeToBuf(buf, payloads_stream);
                    } else {
                        Payload.writeToBuf(buf, payloads_stream, lcp);
                    }
                }

            }
        }


        public static void main(String[] args) {

            Random random = new Random();


            Segment seg = Segment.createSegment();
            Payload payload = new Payload();
            payload.offset = 100;
            payload.length = 2;
            payload.data = Unpooled.copiedBuffer(new byte[payload.length]);
            seg.payloads_stream.add(payload);
//            seg.cmd |= CMD_PACKET_STREAM;
//            seg.sn = 1;
//            seg.una = 2;
            ByteBuf buf = Unpooled.buffer();
            seg.writeToBuf(buf);
            System.out.println(buf.readableBytes());
            System.out.println(seg.computeLength());

            Segment segment = readFromBuf(buf);
            System.out.println(buf.readableBytes());
            System.out.println(segment);


        }


    }

    public byte[] copyBytes(byte[] arr, int maxLen) {
        return Arrays.copyOf(arr, Math.min(arr.length, maxLen));
    }

    int maxSn = 0;//最大接收到的sn  必须小于rcv_next_sn
    byte[] rcv_ackmask = new byte[0];


    public boolean rcvAckmaskSnIsAck(int sn) {
        if (_itimediff(sn, rcv_next_sn) < 0) {

            return true;
        }
        return AckMaskUtils.getBitInByteArray(_itimediff(sn, rcv_next_sn), rcv_ackmask);

    }

    //标记maskack是 接收时进行的
    public void rcvAckmaskMark(int sn) {

        //TODO 过大的会异常 而且暂时没考虑 sn 只有24位的问题  一定时间后会错误
        //snd_next_sn之后的进行标记
        int diff = _itimediff(sn, rcv_next_sn);
        if (diff < 0) {//错误 忽略//应该是乱序收到的 已经滑动una后。才收到
            System.out.println("忽略错误！" + sn + "  " + diff);
            return;
        }
        rcv_ackmask = AckMaskUtils.setBitInByteArray(diff, rcv_ackmask);//比如sn3 una1 不加1是第二位 应该是第三位 所以需要加1
    }
}
