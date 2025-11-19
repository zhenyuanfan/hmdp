package com.hmdp.utils;

public interface Ilock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁的过期时间
     * @return
     */
    public boolean tryLock(long timeoutSec);
    /**
     * 释放锁
     */
    public void unLock();
}
