package org.example;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import org.junit.Test;

import java.util.*;
import java.util.function.BiFunction;

public class DR {

    /*
     *发送方 状态 0 未发送  1 已发送  2已确认
     * 接收方状态 0 未接受  2 已接收
     *
     */

    public static final int UNSEND = 0;
    public static final int UNACK = 1;
    public static final int ACK = 2;
    @Getter
    int size=0;
    //    public List<Range> ranges = new ArrayList<>();
    public RingArrayList<Range> ranges = new RingArrayList<>();

    /** 获取指定sn的  用来获取时间 计算rtt*/
    public Range getOneWithSn(int sn){
        for (Range range : ranges) {
            if(range.sn==sn){
                return range;
            }
        }
        return null;

    }
    /**
     * 区域是否重叠
     * @param r1start
     * @param r1length
     * @param r2start
     * @param r2length
     * @return
     */
    public static boolean isOverlap(int r1start, int r1length, int r2start, int r2length) {
        int maxStart = Math.max(r1start, r2start);
        int minEnd = Math.min(r1start + r1length, r2start + r2length);
        return maxStart < minEnd;
    }
    /**方便add后 获取最后一个进行设置*/
    public Range last(){
        return ranges.getLast();
    }
    public Range add(int length ){
        return add(length, UNSEND);
    }
    public Range add(int length,int type){
        if(length<1){
            return null;
        }
        Range last=null;
        if(ranges.size()>0){
            last=ranges.get(ranges.size()-1);
        }

        int start=0;
        if(last!=null){
            start=last.start+last.length;
        }
        Range r=(new Range(start,length));
        r.type=type;
        ranges.add(r);
        size+=length;
        return r;
    }
    public void merge(BiFunction<Range,Range,Range> mergeFunc){
        for (int i = 0; i < ranges.size()-1; i++) {
            Range r1=ranges.get(i);
            Range r2=ranges.get(i+1);
            Range r=mergeFunc.apply(r1,r2);
            if(r==null){
                //不合并
            }else{
                //删除原先的

                ranges.remove(i+1);
                ranges.remove(i);
                ranges.add(i,r);
            }
        }
    }
    public void markRangeTypeSn(int start, int length, int type,int sn) {
        split(start);
        split(start+length);
        // 遍历所有范围，找到包含指定范围的范围。
        for (int i = 0; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(isOverlap(cur.start,cur.length,start,length)){
                cur.type=type;
                cur.sn=sn;
            }
        }
    }
    public void markRangen(int start, int length, int type) {
        resetCache();
        markRangen(start,length,type,0);
    }
    public void markRangen(int start, int length, int type,long rto) {
        splitn(start);
        splitn(start+length);
        int i = binarySearch(start);
        if(i>=0){
            while(true){
                Range cur= null;
                if(i>=ranges.size()){
                    return;
                }
                cur = ranges.get(i);

                if(cur.start<start+length){
                    cur.type=type;
                    cur.rto=rto;
                }else{
                    return;
                }
                i++;
            }
        }
    }
    public void markRange(int start, int length, int type,long rto) {
//        split(start);
//        split(start+length);//优化了
        // 遍历所有范围，找到包含指定范围的范围。
        resetCache();
        int total=0;
        for (int i = 0; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(cur==null){
                cur=ranges.get(i);
            }
            if(isOverlap(cur.start,cur.length,start,length)){
                //如果已经是ACK类型了不再分割
//                if(start>=cur.start&&start+length<=cur.start+cur.length){
//                    if(rto==0&&cur.type==type&&type==ACK){
//                        return;//不知道怎么写错了?导致了
//                    }
//                }
                //区域重叠
                //判断头部尾部是否超出
                //先尾.因为这里 没再次获取get(i)
                boolean re=false;
                if(cur.start+cur.length>start+length){
                    split(i,start+length);//截尾
                    re=true;
                }
                if(cur.start<start&&cur.start+cur.length>start){
                    split(i,start);//截头
                    re=true;
                }
                if(re){
                    i--;
                    continue;
                }

                //存在重叠
                cur.type=type;
                cur.rto=rto;
                cur.ts=System.currentTimeMillis();
                total+=cur.length;
                if(total>=length){
                    return;
                }

            }
        }
    }
    public void markRange(int start, int length, int type ) {
        markRange(start,length,type,0);
    }

    public void resetCache(){
        preSearchIdx=0;
        preSearchTs=0;
        cache_lastAckIdx=0;
    }
    /**
     * 向左滑动
     */
    public void leftShift(int distance){
        resetCache();
//        split(distance);
        splitn(distance);
        Iterator<Range> iterator = ranges.iterator();

        this.size=0;
        while(iterator.hasNext()){
            Range next = iterator.next();
            if(next.start+ next.length<=distance){

                iterator.remove();
            }else{
                //size-=distance;
                next.start-=distance;
                size+=next.length;

            }
        }
    }

    int cache_lastAckIdx;

    /**
     * 返回最左边连续的 指定类型 数量
     * @param type
     * @return
     */
    public int leftCanShiftDistance(int type){
        int distance=0;
        int startIdx=cache_lastAckIdx;
        for (int i = startIdx; i < ranges.size(); i++) {
            Range cur=ranges.get(i);
            if(cur.type==type){
                distance=cur.start+cur.length;
                cache_lastAckIdx=i;
            }else{
                break;
            }
        }
        if(distance>size){
            System.out.println("drbug 位置3");
        }
        return distance;
    }

    public List<Range> needCanShiftRanges(int type){
        List<Range> ls=new ArrayList<>();
        Iterator<Range> iterator = ranges.iterator();
        while(iterator.hasNext()){
            Range next = iterator.next();
            if(next.type==type){
                ls.add(next);
            }else{
                break;
            }
        }
        return ls;
    }
    public boolean isAck(int sn){
        for (Range range : ranges) {
            if((range.sn-sn)<=0){
                if(range.sn==sn){
                    return range.type==ACK;
                }
            }else{
                break;
            }
        }
        return false;
    }
    /**
     * 返回滑动距离
     * @param type
     * @return
     */
    public int leftAutoShift(int type){

//
        int distance=leftCanShiftDistance(type);
        if(distance>0){leftShift(distance);}
        return distance;
    }
    private int binarySearch(int pos) {
        int low = 0;
        int high = ranges.size() - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            Range range = ranges.get(mid);

            if (range.start <= pos && range.start + range.length > pos) {
                return mid;
            } else if (range.start > pos) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return -1; // Not found
    }
    /**
     * 从指定位置分割
     *
     * @param pos
     */
    public void splitn(int pos) {

        int i= binarySearch(pos);
        if (i >= 0) {
            split(i,pos);
        }
    }
    //提供下标 和全局位置进行拆分  避免再次循环减少开销(虽然微乎其微优化)
    public void split(int elementIdx,int pos) {
        int i= elementIdx;
        if (i >= 0) {
            Range cur = ranges.get(i);
            if(isOverlap(cur.start,cur.length,pos,1)){

            }else{
                System.out.println("错误");
            }
            // 分割范围
            Range r1 = cur.copy();
            r1.start = cur.start;
            r1.length = pos - cur.start;
            Range r2 = cur.copy();
            r2.start = pos;
            r2.length = cur.length - (pos - cur.start);
//            ranges.remove(i);

            if(r2.length>0){
                ranges.set(i,r2);
            }
            if(r1.length>0){
                ranges.add(i,r1);
            }
            return;
        }
    }

    public void split (int pos) {
        int index = binarySearch(pos);
        // 获取所在的区域
        for (int i = 0; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if (cur.start <= pos && cur.start + cur.length > pos) {
                // 分割范围
                Range r1 = cur.copy();
                r1.start = cur.start;
                r1.length = pos - cur.start;
                Range r2 = cur.copy();
                r2.start = pos;
                r2.length = cur.length - (pos - cur.start);
//                ranges.remove(i);

                if(r2.length>0){
                    ranges.set(i,r2);//不删除再添加了 减少一次拷贝 第一个直接set 第二个add 只触发一次拷贝
                }
                if(r1.length>0){
                    ranges.add(i,r1);
                }
                return;
            }
        }
    }

    public static void main(String[] args) {
        //发送数据的流程
        //初始化 开始下标
        //写入缓冲区 更新窗口大小 添加Range
        //收到数据  ack 滑动窗口
        //发送时  取出需要长度的范围 标记为 已发送 和发送 时间，发送次数
        //定期检查 是否有超时的数据  如果远程的可接受窗口足够 则发送
        //窗口大小只是调用send的时候 是否有剩余空间，而不会检查是否超过
        //流控 交给其他控制  这里不管。

        System.out.println(isOverlap(0, 10, 10, 20));
        System.out.println(isOverlap(0, 10, 9, 20));
        System.out.println(isOverlap(0, 10, 5, 4));
        DR r = new DR();
        r.markRange(10,10,3);

        System.out.println(r.ranges.size());

        r.markRange(5,10,2);

        System.out.println(r.ranges.size());

        System.out.println("测试 取特定区域");
        List<Range> rs = r.needRanges(1024,2);

        System.out.println(rs.size());
        System.out.println(r.sumTotalSize(rs));
        System.out.println("左移动");
        r.leftShift(5);
        System.out.println("?");

    }
    public static int sumTotalSize(List<Range> rs){
        int count = 0;
        for (int i = 0; i < rs.size(); i++) {
            count+=rs.get(i).length;
        }
        return count;

    }
    public static class TestC{
        @Test
        public void DataRange模拟发送接收() throws InterruptedException {
            ByteBuf buffer = Unpooled.buffer();
            ByteBuf recBuf=Unpooled.buffer();

            int offset=0;
            DR dr = new DR();

            byte[]allData=new byte[1024*1024*50];//50M
            Random rand=new Random(1);
            rand.nextBytes(allData);
            List<Range> before=new ArrayList<>();
            dr.add(allData.length);
            for (int i = 0; i < 9999; i++) {
                System.out.println("当前偏移"+offset);;
                if(offset== allData.length){
                    System.out.println("全部传输完毕");
                    break;
                }
                //模拟发送  和ack 和丢包
                //最终检验是否全部都ack
                //获取已经发送的区域 ，随机范围ACK
                //随机ACK
                //获取未确认的  随机ACK
                {
                    List<Range> ranges = dr.needRanges(-1, 1);
                    int lostCount=0;
                    for (Range range : ranges) {
                        if(Math.random()<0.5){
                            //丢包
                            lostCount++;
                        }else{
                            dr.markRange(range.start,range.length,2);//确认收到
                        }
                    }

                    List<Range> list = dr.needRanges(-1, 2);
                    if(dr.size<0){
                        System.out.println("异常位置1");
                    }
                    int distance=dr.leftAutoShift(2);
                    if(dr.size<0){
                        System.out.println("异常位置2");
                    }
                    System.out.println("滑动的距离"+distance+" 丢包数量"+lostCount);
                    offset+=distance;


                }


                System.out.println("----i-----"+i);
                //获取已经发送的大小
                List<Range> ranges = dr.needRanges(dr.size,1);
                int size = dr.sumTotalSize(ranges);
                System.out.println("需要发送的size"+size);
                List<Range> waitRanges = dr.needRanges(-1, 1);
                int unack=sumTotalSize(waitRanges);
                System.out.println("等待确认的数量"+unack);
                List<Range> resendRanges = dr.needRanges(-1, 1,System.currentTimeMillis()-500);
                System.out.println("超时需要重发的数量"+sumTotalSize(waitRanges));
                int a=rand.nextInt(409700-128)+128;
//                a=Math.min(a,size);//随机大小确认

                System.out.println("本次准备发送的数据量"+a);
                List<Range> sendList=dr.needRanges(a,1);//获取未确认的
                a-=dr.sumTotalSize(sendList);
                if(a==0){
                    System.out.println("本次只发送需要重发的");
                    //更新ts时间
                    for (Range range : sendList) {
                        range.ts=System.currentTimeMillis();
                        range.type= DR.UNACK;
                    }
                }else{
                    //获取未发送的
                    List<Range> unsendList = dr.needRanges(a, 0);
                    System.out.println("首次发送的数据数量"+sumTotalSize(unsendList));
                    for (Range range : unsendList) {
                        range.type= DR.UNACK;
                        range.ts=System.currentTimeMillis();
                    }
                    sendList.addAll(unsendList);
                    before=sendList;
                }
                System.out.println("本次实际发送数量:"+sumTotalSize(sendList));

                Thread.sleep(300);


            }
            System.out.println("dir.size"+dr.size);


        }
    }

    @Test
    public void ms(){
        ByteBuf buffer = Unpooled.buffer();
        ByteBuf recBuf=Unpooled.buffer();

        int offset=0;
        DR dr = new DR();

        byte[]allData=new byte[1024*1024*50];//50M


        Random random = new Random();
        PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(() -> {

            dr.markRange(random.nextInt(999999),random.nextInt(999999),random.nextInt(3));


            dr.needRanges(random.nextInt(999999), random.nextInt(3));
        }, null, 1000L);
        System.out.println("test.times = " + test.times);
        System.out.println("test.useTime = " + test.useTime);


    }

    /**
     * 会切分并返回 范围内的
     * @param start
     * @param length
     * @return
     */
    public List<Range> getRanges(int start, int length) {
        split(start);
        split(start+length);
        List<Range> rs=new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(isOverlap(cur.start,cur.length,start,length)){
                rs.add(cur);
            }
        }
        return rs;
    }
    public boolean rangeCheck(int start,int len,int type){

        int rtotal=0;
        splitn(start);
        splitn(start+len );
//        List<Range> rs=new ArrayList<>();
        int startIdx=binarySearch(start);
        if(startIdx<0){
            return false;
        }
        for (int i = startIdx; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(cur.start>start+len){
                return false;
            }
            if(isOverlap(cur.start,cur.length,start,len )){
                if(cur.type==type){
                    rtotal+=cur.length;
                    if(len==rtotal){
                        return true;
                    }
                }
            }else{
                return false;
            }
        }
        return false;
    }
    public int sumLenRange(int start,int len,int type){

        int rtotal=0;
        split(start);
        split(start+len );

        for (int i = 0; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(cur.start>start+len){
                break;
            }
            if(isOverlap(cur.start,cur.length,start,len )){
                if(cur.type==type){
                    rtotal+=cur.length;

                }
            }else{

            }
        }
        return rtotal;
    }

    public List<Range> needRanges(int needLength,int type) {

        return needRanges(needLength,false,type,type);
    }
    @Getter@Setter
    /**
     * 记录上次needOne搜索到的位置,下次用来加速
     */
            int preSearchIdx=0;
    long preSearchTs=0;
    /**
     * 不会超过limit  返回第一个满足条件的部分
     * needLength 必须小于=limit
     */
    public Range needOne(int needLength,int limit, int type,int type2,long curTs) {
//        split(limit);
        if(needLength==-1){
            needLength=Integer.MAX_VALUE;
        }
        if(needLength <0){
            System.out.println("异常needLength<0");
            return null;
        }
//        needLength=Math.min(needLength,limit);
        int totalLen=0;
        for (int i = 0; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(cur.start>=limit){
                preSearchIdx=i;
                return null;
            }
            if(cur.type!=type&&cur.type!=type2){
                continue;
            }
            if(cur.ts>(curTs-cur.rto) && cur.type==UNACK){//没到时间的  未确认跳过...(这个方法写的有点怪 混合一起但是 过于交叉)
//                System.out.println(cur.ts-(curTs-cur.rto));
                //emmm
                continue;
            }
            if(cur.start>=limit){//开始位置超过限位 返回null
                preSearchIdx=i;
                return null;
            }

//            int beforeSize = sumTotalSize(ranges);

            //以最小的位置分割
            int pos=Math.min(limit,cur.start+needLength);//写错了?
            if(cur.length>needLength||cur.start+cur.length>limit){
                split(i,pos);
                cur=ranges.get(i);
            }
            if(cur.length>needLength){
                System.out.println("drbug1");
            }
//            int afterSize=sumTotalSize(ranges);
//            if(beforeSize!=afterSize){
//                System.out.println("drbug2");//
//            }

//            if(cur.start+cur.length>limit){//start再限位之前 但是末端超过限位  拆分
//                split(i,limit);
//                cur=ranges.get(i);
//            }
//            if(cur.length>needLength){
//                split(i,cur.start+needLength);
//                cur=ranges.get(i);
//            }
            preSearchIdx=i;
            return cur;
        }
        return null;
    }
    public Range needOneUseCache(int needLength,int limit, int type,int type2,long curTs) {
//        split(limit);
        if(needLength==-1){
            needLength=Integer.MAX_VALUE;
        }
        if(needLength <0){
            System.out.println("异常needLength<0");
            return null;
        }
//        needLength=Math.min(needLength,limit);
        int totalLen=0;
        int startIdx=0;
        if(preSearchTs==curTs){
            startIdx=preSearchIdx;
        }
        preSearchTs=curTs;


        for (int i = startIdx; i < ranges.size(); i++) {
            Range cur = ranges.get(i);
            if(cur.start>=limit){
                preSearchIdx=i;
                return null;
            }
            if(cur.type!=type&&cur.type!=type2){
                continue;
            }
            if(curTs-cur.ts<cur.rto){
                //emmm
                continue;
            }
            if(cur.start>=limit){//开始位置超过限位 返回null
                preSearchIdx=i;
                return null;
            }
//            int beforeSize = sumTotalSize(ranges);

            //以最小的位置分割
            int pos=Math.min(limit,cur.start+needLength);//写错了?
            if(cur.length>needLength||cur.start+cur.length>limit){
                split(i,pos);
                cur=ranges.get(i);
            }
            if(cur.length>needLength){
                System.out.println("drbug1");
            }
//            int afterSize=sumTotalSize(ranges);
//            if(beforeSize!=afterSize){
//                System.out.println("drbug2");//
//            }

//            if(cur.start+cur.length>limit){//start再限位之前 但是末端超过限位  拆分
//                split(i,limit);
//                cur=ranges.get(i);
//            }
//            if(cur.length>needLength){
//                split(i,cur.start+needLength);
//                cur=ranges.get(i);
//            }
            preSearchIdx=i;
            return cur;
        }
        return null;
    }
    public List<Range> needRanges(int needLength,boolean onlyOne,int type,int type2) {
        if(needLength==-1){
            needLength=Integer.MAX_VALUE;
        }
        if(needLength <0){
            System.out.println("异常needLength<0");
            return null;
        }
        List<Range> rs=new ArrayList<>();
        int totalLen=0;
        for (int i = 0; i < ranges.size()&&totalLen !=needLength; i++) {
            if(onlyOne==true&&rs.size()==1){
                return rs;
            }
            Range cur = ranges.get(i);
            if(cur.type!=type&&cur.type!=type2){
                continue;
            }
            int curLen=Math.min(needLength,cur.length);//取还能截取多少
            //如果不是整个对象 则拆分
            if(needLength==cur.length){//如果整个对象
                rs.add(cur);
                needLength-=cur.length;
                break;
            }else if(needLength<cur.length){//切分
                split(cur.start+needLength);
                i--;continue;
            }else{//直接添加
                rs.add(cur);
                needLength-=cur.length;
                continue;
            }


        }
        return rs;
    }
    /**
     * 之前在leftshift方法里加减 计算size  貌似错了，然后单独写的这个验证，用不到了
     */
    private void updateSize(){
        size=0;
        for (int i = 0; i < ranges.size(); i++) {
            size+=ranges.get(i).length;
        }

    }
    public List<Range> needRanges(int needLength,int type,long ts) {
        if(needLength==-1){
            needLength=Integer.MAX_VALUE;
        }
        List<Range> rs=new ArrayList<>();
        int totalLen=0;
        for (int i = 0; i < ranges.size()&&totalLen !=needLength; i++) {
            Range cur = ranges.get(i);
            if(cur.rto>ts-cur.ts){//没到时间的不取
                continue;
            }
            if(cur.type!=type){
                continue;
            }
            int curLen=Math.min(needLength,cur.length);//取还能截取多少
            //如果不是整个对象 则拆分
            if(curLen!=cur.length){
                split(cur.start+curLen);
            }

            rs.add(ranges.get(i));
        }
        return rs;
    }
    public List<Range> needRangeWithTimeout(int needLength,int type,long curTs) {
        if(needLength==-1){
            needLength=Integer.MAX_VALUE;
        }
        List<Range> rs=new ArrayList<>();
        int totalLen=0;
        for (int i = 0; i < ranges.size()&&totalLen !=needLength; i++) {
            Range cur = ranges.get(i);

            if(cur.ts>curTs-cur.rto){//超时了 需要重传
                continue;
            }

            if(cur.type!=type){
                continue;
            }
            int curLen=Math.min(needLength,cur.length);//取还能截取多少
            //如果不是整个对象 则拆分
            if(curLen!=cur.length){
                split(cur.start+curLen);
            }

            rs.add(ranges.get(i));
        }
        return rs;
    }

    public static class Range {
        //失败次数
        public int failCount;
        public int sendcount;

        public Range(int start, int length) {
            this.start = start;
            this.length = length;
        }

        public int start;
        public int length;
        public int type;
        public long ts;//发送时间
        //        public ByteBuf buf;//用不到 只用来标记包的信息  和数据的范围即可。缓冲区只用一个
        public int windid;//窗口id
        public long sn;//包序号
        //序号 只能使用一次

        public long rto;//超时重传时间
        /**
         * 快速重传用,记录序号被跳过数量.如果超过则无视rto 直接重传
         */
        public int skipCount;

        public Range copy() {
            Range r = new Range(start, length);
            r.type = this.type;
            r.ts=this.ts;
            r.sn=this.sn;
            r.sendcount=this.sendcount;
//            r.skipCount=0;


            r.rto=this.rto;
            r.failCount=this.failCount;

            return r;

        }

        @Override
        public String toString() {
            return "Range{" +
                    "sendcount=" + sendcount +
                    ", start=" + start +
                    ", length=" + length +
                    ", type=" + type +
                    ", ts=" + ts +
                    ", sn=" + sn +
                    ", rto=" + rto +
                    '}';
        }
    }
}
