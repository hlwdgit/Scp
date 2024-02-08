package org.example;


import java.util.Arrays;

public class AckMaskUtils {
    static byte[] rcv_ackmask = new byte[0];
    static int rcv_next_sn;

    public AckMaskUtils() {
    }
    public static int getRightLastOneBitIndex(byte[] byteArray) {
        return getRightLastOneBitIndex(byteArray, byteArray.length - 1);
    }

    public static int getRightLastOneBitIndex(byte[] byteArray, int startIndex) {
        int endBitIndex = -1;

        for(int i = startIndex; i >= 0 && getBitInByteArray(i, byteArray); endBitIndex = i--) {
        }

        return endBitIndex;
    }


    public static byte[] setBitInByteArray(int index, byte[] byteArray) {
        int byteIndex;
        if (index >= byteArray.length * 8) {
            byteIndex = index / 8 + 1;
            byte[] newByteArray = new byte[byteIndex];
            System.arraycopy(byteArray, 0, newByteArray, 0, byteArray.length);
            byteArray = newByteArray;
        }

        byteIndex = index / 8;
        int bitIndex = index % 8;
        byteArray[byteIndex] = (byte)(byteArray[byteIndex] | 1 << bitIndex);
        return byteArray;
    }

    public static boolean getBitInByteArray(int index, byte[] byteArray) {
        int byteIndex = index / 8;
        int bitIndex = index % 8;
        if (byteIndex >= byteArray.length) {
            return false;
        } else {
            return (byteArray[byteIndex] & 1 << bitIndex) > 0;
        }
    }

    public static int getMaxOneBitIndex(byte[] byteArray) {
        int lastIndex = byteArray.length * 8 - 1;
        int endBitIndex = -1;

        for(int i = lastIndex; i >= 0; --i) {
            if (getBitInByteArray(i, byteArray)) {
                endBitIndex = i;
                break;
            }
        }

        return endBitIndex;
    }

    public static int getLeftLastOneBitIndex(byte[] byteArray) {
        return getLeftLastOneBitIndex(byteArray, 0);
    }

    public static int getLeftLastOneBitIndex(byte[] byteArray, int startIndex) {
        int endBitIndex = -1;

        for(int i = startIndex; i < byteArray.length * 8 && getBitInByteArray(i, byteArray); endBitIndex = i++) {
        }

        return endBitIndex;
    }

    public static byte[] slideByteArray(int distance, byte[] byteArray) {
        if (distance == 0) {
            return byteArray;
        } else if (distance < 0) {
            throw new IllegalArgumentException("distance must >=0 param is:" + distance);
        } else {
            int endBitIndex = getMaxOneBitIndex(byteArray);
            int saveBitsLen = endBitIndex + 1 - distance;
            if (saveBitsLen <= 0) {
                return byteArray.length == 0 ? byteArray : new byte[0];
            } else {
                int byteIndex = saveBitsLen / 8;
                int bitIndex = saveBitsLen % 8;
                byte[] newArr = new byte[bitIndex == 0 ? byteIndex : byteIndex + 1];

                for(int i = 0; i <= saveBitsLen; ++i) {
                    if (getBitInByteArray(i + distance, byteArray)) {
                        newArr = setBitInByteArray(i, newArr);
                    }
                }

                return newArr;
            }
        }
    }

    public static void rcvAckmaskMark(int sn) {
        if (sn == 7) {
            System.out.println("zt");
        }

        int diff = sn - rcv_next_sn;
        if (diff < 0) {
            System.out.println("忽略错误！" + sn + "  " + diff);
        } else {
            rcv_ackmask = setBitInByteArray(diff, rcv_ackmask);
        }
    }

    private static void slideAckMask(int distance) {
        if (distance != 0) {
            rcv_ackmask = slideByteArray(distance, rcv_ackmask);
            System.out.println("ackmask 滑动距离----" + distance);
        }
    }

    private static void autoSlideAckMask() {
        int distance = getLeftLastOneBitIndex(rcv_ackmask);
        if (distance >= 0) {
            rcv_next_sn += distance + 1;
            slideAckMask(distance + 1);
        }
    }

    public static int limit24B(int val) {
        return val & 16777215;
    }

    public static void main(String[] args) {
        rcv_next_sn = 1;
        rcvAckmaskMark(3);
        System.out.println(Arrays.toString(rcv_ackmask));
        rcvAckmaskMark(7);
        System.out.println(Arrays.toString(rcv_ackmask));

        int v;
        for(v = 0; v < rcv_ackmask.length * 8; ++v) {
            if (getBitInByteArray(v, rcv_ackmask)) {
                System.out.println("标记的是" + (rcv_next_sn + v));
            }
        }

        System.out.println("----");
        rcv_next_sn = 54;
        rcv_ackmask = new byte[]{-14, 73, 1, 95, -104, 8, -117, -65, 29, 124, 1};

        for(v = 0; v < rcv_ackmask.length * 8; ++v) {
            if (getBitInByteArray(v, rcv_ackmask)) {
                System.out.println("标记的是" + (rcv_next_sn + v));
            }
        }

        rcv_next_sn += 78;
        slideAckMask(78);
        System.out.println(Arrays.toString(rcv_ackmask));
        autoSlideAckMask();
        System.out.println(Arrays.toString(rcv_ackmask));
        System.out.println(rcv_next_sn);

        for(v = 0; v < rcv_ackmask.length * 8; ++v) {
            if (getBitInByteArray(v, rcv_ackmask)) {
                System.out.println("标记的是" + (rcv_next_sn + v));
            }
        }

        v = 16777210;

        for(int i = 0; i < 10; ++i) {
            v = limit24B(v + 1);
            System.out.println(v);
        }

        System.out.println("----");
        rcv_ackmask = new byte[]{0, -1};
        autoSlideAckMask(8);
        System.out.println(Arrays.toString(rcv_ackmask));
    }

    private static void autoSlideAckMask(int minDistance) {
        int distance = Math.max(getLeftLastOneBitIndex(rcv_ackmask, minDistance), minDistance);
        if (distance >= 0) {
            rcv_next_sn += distance + 1;
            slideAckMask(distance + 1);
        }
    }
}