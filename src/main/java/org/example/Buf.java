package org.example;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class Buf {
    int cIdex;//当前第几个元素
    int cOffset;
    int cSize = 1024 * 4;
    RingArrayList<ByteBuf> components = new RingArrayList();
    int readerIndex;
    int writerIndex;
    int capacity;

    public int capacity() {
        return capacity;
    }

    public int capacity(int newcapacity) {
        //增加
        while (this.capacity < newcapacity) {
            components.add(Unpooled.wrappedBuffer(new byte[cSize]));
            this.capacity += cSize;
        }
        return this.capacity;
    }

    public Buf readerIndex(int readerIndex) {
        if(readerIndex>=0&&writerIndex>=readerIndex&&capacity>=writerIndex){

        }else{
            throw new IndexOutOfBoundsException("indexError readerIndex:"+readerIndex+",writerIndex:"+writerIndex+",capacity:"+capacity);
        }

        this.readerIndex = readerIndex;
        return this;
    }

    public Buf writerIndex(int writerIndex) {
        if(readerIndex>=0&&writerIndex>=readerIndex&&capacity>=writerIndex){

        }else{
            throw new IndexOutOfBoundsException("indexError readerIndex:"+readerIndex+",writerIndex:"+writerIndex+",capacity:"+capacity);
        }
        this.writerIndex = writerIndex;
        return this;
    }

    int baseOffset=0;

    /**
     * 释放多余的 buf
     */
    public void discardComponents(){
        //计算最后一个的下标
        int cidx= realWriterIndex() /cSize;
        for (int i = cidx+1; i <components.size; i++) {
            ByteBuf buf = components.removeLast();
            buf.release();
            this.capacity-=cSize;
//            System.out.println("释放组件");
        }
    }
    public void discardReadBytes() {//不需要discard貌似使用本来就是循环的
        baseOffset+=readerIndex;
        writerIndex-=readerIndex;
        readerIndex=0;
        if(writerIndex==0){
            baseOffset=0;
            return;
        }
        int distance=baseOffset/cSize;
        for (int i = 0; i < distance; i++) {
//                System.out.println("移动距离"+distance);
            //循环使用
            ByteBuf buf = components.removeFirst();
            buf.clear();
            components.addLast(buf);
            baseOffset-=cSize;
        }
    }
    public int readerIndex(){
        return   readerIndex;
    }
    public int writerIndex(){

        return    writerIndex ;
    }
    public int realReaderIndex(){
        return  baseOffset+readerIndex;
    }
    public int realWriterIndex(){

        return  baseOffset+writerIndex ;
    }
    public void readBytes(ByteBuf buf ){
        readBytes(buf, this.readableBytes());
    }
    public void readBytes(ByteBuf buf, int len){
        int remain = len;
        do {

            int cidx=realReaderIndex()/cSize;
            int offset = realReaderIndex() % cSize;
            int writerLen = Math.min(cSize - offset,remain);
            ByteBuf first = components.get (cidx%components.size);
            first.writerIndex(cSize);//之前写入修改了 writerIndex  ,不设置会读不了 小于writerIndex
            first.readerIndex( offset);

            buf.writeBytes(first,writerLen);

            readerIndex += writerLen;

            remain-=writerLen;
        } while (remain > 0);

    }
    public int readableBytes(){
        return writerIndex-readerIndex;
    }
    public void writeBytes(ByteBuf buf ) {
        writeBytes(buf,buf.readableBytes());
    }
    public void writeBytes(ByteBuf buf,int len) {
        if (len + writerIndex > capacity) {
            //扩容
            capacity( len + writerIndex);
        }
        int remain = len;
        do {
            int cidx=realWriterIndex()/cSize;
            int offset = realWriterIndex() % cSize;
            int writerLen = Math.min(cSize - offset,remain);
            ByteBuf first = components.get (cidx%components.size);
            first.readerIndex(0);
            first.writerIndex(offset);
            int start=len - remain;
            first.writeBytes(buf, writerLen);
            writerIndex += writerLen;

            remain-=writerLen;
        } while (remain > 0);
    }
    public void writeBytes(byte[] bytes) {
        if (bytes.length + writerIndex > capacity) {
            //扩容
            capacity(bytes.length + writerIndex);
        }
        int remain = bytes.length;
        do {
            int cidx=realWriterIndex()/cSize;
            int offset = realWriterIndex() % cSize;
            int writerLen = Math.min(cSize - offset,remain);
            ByteBuf first = components.get (cidx%components.size);
            first.readerIndex(0);
            first.writerIndex(offset);
            int start=bytes.length - remain;
            first.writeBytes(bytes, start, writerLen);
            writerIndex += writerLen;

            remain-=writerLen;
        } while (remain > 0);
    }

    public static void main(String[] args) {


        Buf buf = new Buf();
        ByteBuf b = Unpooled.wrappedBuffer(new byte[10]);
//        buf.writeBytes(new byte[4096+1]);
//        buf.writeBytes(b,10);

        byte[] testData=new byte[4098];
        for (int i = 0; i < testData.length; i++) {
            testData[i]= (byte) i;
        }
        buf.writeBytes(testData);
        ByteBuf c = Unpooled.buffer();
        buf.readBytes( c,4096);
        buf.discardReadBytes();
        c.clear();
        buf.readBytes( c,2);

        buf.discardReadBytes();
        System.out.println(buf.writerIndex);
//        buf.readerIndex (1000);
//        buf.writerIndex (10000);


        for (int i = 0; i < 10; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(() -> {
                buf.writeBytes(testData);
                c.clear();
                buf.discardReadBytes();
                buf.readBytes( c,4096);
                c.clear();
                buf.discardReadBytes();
                buf.readBytes( c,2);
                buf.discardReadBytes();
//                buf.discardComponents();
            }, null, 1000l);
            System.out.println("test.times = " + test.times);
            System.out.println("test.times = " + test.times*testData.length/1024/1024.0);
        }
        //性能可以满足需求
    }


}
