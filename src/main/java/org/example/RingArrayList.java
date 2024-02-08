package org.example;



import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

/**
 * 一个简单的 循环数组。有bug
 * 最好容量不要过大 比如达到1<<31 逼近最大值。应该会错误。因为没做 扩容检查。和其他处理
 * 没思考这个情况  可能有bug也可能没
 * @param <T>
 */
public class RingArrayList<T> extends AbstractList<T> {
    Object[] items;

    int size;
    int offset;
    int bits;
    int mask;
    public RingArrayList() {

        this(2);
    }
    //1 2 4 8 16 ....
    public RingArrayList(int bits) {
        this.bits=bits;
        items=new Object[1<<bits];
        for (int i = 0; i < bits; i++) {

            mask<<=1;

            mask|=1;
        }
    }

    public int getCapacity(){
        return items.length;
    }
    public int size(){
//        if(maxIdx==offset){
//            return 0;
//        }
//        return nextIdx -offset;
        return size;
    }
    public RingArrayList(T[] items) {
        this.items = items;
    }

    //XXXXXXXXX访问时 抛出异常。添加时  不抛出 用来判断 是否末尾。如果是 则添加
    //0~items.length(包含)
    public int toRealIdx(int idx){//,boolean exception){

        if(idx<0||idx>items.length){
            throw new IndexOutOfBoundsException(""+idx);
        }
        return (idx+offset)& mask;
    }
    public void addFirst( T obj){
        addCheck();
        if(size()==0){
            add(obj);
            return;
        }
        offset=fixedRange(offset-1);
        size++;
        items[offset]=obj;
    }
    public T removeFirst() {
        T r= (T) items[offset];
        items[offset]=null;
        offset=fixedRange(offset+1);
        size--;
        return r;
    }

    /**
     * 限制在范围内
     * 比如 2bits  0~3
     * -1 得到3
     * -2 2
     * -3 1
     * -4 0
     * 整倍数都是0
     * @param val
     * @return
     */
    public int fixedRange(int val){
        return val<<(32-bits)>>>(32-bits);
    }

    public void add(int idx,T obj){
        addCheck();
        if(idx==size()){
            add(obj);
            return;
        }
        int realIdx=toRealIdx(idx);
        if(idx==0){
            addFirst(obj);
            return;
        }

        {
            //只能进行拷贝了
            //调用数组拷贝方法  只能分2段拷贝
//            先手动拷贝
            //需要拷贝的数量。
//            System.out.println("插入拷贝");
            int copyCOunt=size-idx;
            //循环拷贝方式
            for (int i = 0; i < copyCOunt; i++) {
                items[toRealIdx(size-i)]=items[toRealIdx(size-1-i)];
            }

//            //arraycopy 方式
//            int sourceIdx=toRealIdx(idx);
//            int destIdx=toRealIdx(idx+1);
//            int lastIndex=toRealIdx(size-1);
////            int rightIdx=Math.min(offset+size-1,items.length-1);
////            System.out.println("---");
////            System.out.println("sourceIdx = " + sourceIdx);
////            System.out.println("destIdx = " + destIdx);
////            System.out.println("lastIndex = " + lastIndex);
////            System.out.println("rightIdx = " + rightIdx);
//            if(destIdx<sourceIdx){//溢出
//                Object last=items[items.length-1];
//                System.arraycopy(items,sourceIdx,items,sourceIdx+1,items.length-1-sourceIdx);
//                if(lastIndex<sourceIdx){
//                    //左边有需要拷贝的
//                    System.arraycopy(items,destIdx,items,destIdx+1,lastIndex-destIdx);
//                    System.out.println("左边需要拷贝"+(lastIndex-destIdx));
//                }
//                items[0]=last;
//            }else{
//                System.arraycopy(items,sourceIdx,items,sourceIdx+1,destIdx-sourceIdx);
//            }




            size++;
//            拷贝完成添加obj
            items[realIdx]=obj;
        }

    }
    public boolean isFull(){
        return size()==getCapacity();
    }

    public void addCheck(){
        if(isFull()){
            //扩容
//            System.out.println("RingArr扩容");
            bits=bits+1;
            Object[] objects = new Object[1<<bits];

            for (int i = 0; i < size; i++) {
                objects[i]=get(i);
            }
            offset=0;

            items=objects;
            mask<<=1;
            mask|=1;
        }
    }
    public boolean add(T obj){
        addCheck();
        int addIdx=toRealIdx(size);
        items[addIdx]=obj;
        size++;
        return true;
    }
    public T set(int idx, T obj){
//        int index=toRealIdx(idx);
//        if(index==size){
//            throw new IndexOutOfBoundsException();
//        }
//        items[index]=obj;
        items[toRealIdx(idx)]=obj;
        return obj;
    }
    public T get(int idx)
    {

        if(idx>=size){
            throw new IndexOutOfBoundsException();
        }
        return (T)items[toRealIdx(idx)];
    }
    public T getAndSetNull(int idx)
    {
        if(idx>=size){
            throw new IndexOutOfBoundsException();
        }
        int index=toRealIdx(idx);
        T r= (T)items[index];
        items[index]=null;
        return r;
    }


    //如果头部有null 则滑动 返回新的size
    public int slide(){

        int nullCount=0;
        for (int i = 0; i < size; i++) {
            if(get(i)==null){
                nullCount++;
            }else{
                break;
            }
        }
        offset=fixedRange(offset+nullCount);
        size-=nullCount;
        return size();

    }
    public T remove(int idx){
        //如果删除和的是 offset  且后面还有元素  则移动
        if(idx==0){
            return removeFirst();
        }if(idx==size()){
            return removeLast();
        }

        if(size()>0){
            int realIdx=toRealIdx(idx);
            T r= (T) items[realIdx];
            items[realIdx]=null;
            //拷贝
            int copyCOunt=size-idx-1;
            for (int i = 0; i < copyCOunt; i++) {
                items[toRealIdx(idx+i)]=items[toRealIdx(idx+1+i)];
            }

//            int overflow = size+offset-items.length;
//            //溢出的大小
//            int sourceIdx=toRealIdx(idx+1);
//            int destIdx=toRealIdx(idx);
//            if(overflow>-1){//溢出
////                    获取最左一个
//                Object last=items[0];
//
//                System.arraycopy(items,1,items,0,overflow);
////                if(sourceIdx>0){
//                System.arraycopy(items,sourceIdx+1,items,sourceIdx,copyCOunt-overflow);//最后一个被覆盖了
////                }
//                //头部的开始拷贝
//                items[items.length-1]=last;
//                items[sourceIdx+copyCOunt-overflow]=null;
////                    把刚才末尾的 放到头部
//
//            }else{
//                try {
//                    System.arraycopy(items,sourceIdx,items,sourceIdx-1,copyCOunt);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }
//            }


            size--;

            return r;
        }else{
            return null;
        }
    }

    public T removeLast() {
        int idx=toRealIdx(size()-1);
        T r= (T) items[idx];
        items[idx]=null;
        --size;
        return r;
    }

    public static void main(String[] args) {
        RingArrayList<Object> arr = new RingArrayList<>();
        System.out.println("arr.fixedRange(-1) = " + arr.fixedRange(-1));
        System.out.println("arr.size() = " + arr.size());
        arr.add(1);
        arr.add(2);
        System.out.println("arr.size() = " + arr.size());
        System.out.println(arr.remove(0));
        System.out.println("arr.size() = " + arr.size());
        System.out.println(arr.remove(0));

        arr.add(1);
        arr.add(2);
        arr.add(3);
        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));
        arr.add(4);
        arr.add(5);

        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));
        System.out.println("插入");
        arr.add(1,"你好");
        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));
        for (int i = 0; i < 10; i++) {
            arr.add("_"+i+"");
        }
        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));
        System.out.println("测试删除");
        arr.remove(1);
        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));
        arr.removeFirst();
        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));
        arr.set(1,null);
        arr.slide();

        arr.add("-1");
        arr.add("-2");
        arr.removeLast();
        System.out.println(arr.size());
        System.out.println(Arrays.toString(arr.items));

        Integer val=1;

        System.out.println("---------");


        System.out.println(arr.fixedRange(-1));
        DR dr = new DR();
        dr.add(1000);
        dr.split(100);
        dr.split(200);
        dr.leftShift(100);
        dr.split(100);
        List<DR.Range> ranges = dr.getRanges(200, 800);
        System.out.println();



    }



}
