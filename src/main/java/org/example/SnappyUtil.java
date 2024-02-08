package org.example;


import org.xerial.snappy.Snappy;

import java.util.Random;


public class SnappyUtil {

    public static byte[] compress(byte bytes[]) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return Snappy.compress(bytes);
        } catch (Exception e) {

            return null;
        }
    }

    public static byte[] unCompress(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return Snappy.uncompress(bytes);
        } catch (Exception e) {

            return null;
        }
    }

    public static void main(String[] args) {

        byte[] b100m=new byte[1024*1024*100];
        byte[] b2=SnappyUtil.compress(b100m);
        System.out.println("100m空数据压缩后"+b2.length);
        Random rand = new Random();
        final byte[] data=new byte[256];
        rand.nextBytes(data);
        for (int i = 0; i < data.length/2; i++) {
            data[i ]= (byte) 1;
        }
        byte[] compress = SnappyUtil.compress(data);
        byte[] bytes = SnappyUtil.unCompress(compress);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i]= (byte) ~bytes[i];
        }
        byte[] compress2 = SnappyUtil.compress(data);

        System.out.println(compress.length);
        System.out.println(compress2.length);
        System.out.println(bytes.length);

        rand.nextBytes(data);
        for (int i = 0; i < 1; i++) {
            PerformanceTestUtils.TestResult test = PerformanceTestUtils.test(()->{
//                byte[] b = SnappyUtil.unCompress(b2);
//                if(b!=null){
//                    System.out.println(b.length);
//                }

                SnappyUtil.unCompress(compress);
            },null,1000l);
            System.out.println("test.times = " + test.times);
        }

        byte[] ackmask=new byte[]{0, 0, 0, 0, -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        byte[] compress1 = SnappyUtil.compress(ackmask);
        System.out.println(ackmask.length);
        System.out.println(compress1.length);


    }
}