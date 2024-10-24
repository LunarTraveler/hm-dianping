package com.hmdp.utils;

public interface ILock {

    public boolean tryLock(long timeout);

    public void unlock();

}
