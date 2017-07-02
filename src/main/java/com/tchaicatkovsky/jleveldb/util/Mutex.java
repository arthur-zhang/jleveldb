package com.tchaicatkovsky.jleveldb.util;

import java.util.concurrent.locks.ReentrantLock;

public class Mutex {
	ReentrantLock lock;
	
	public Mutex() {
		lock = new ReentrantLock();
	}
	
	public void lock() {
		lock.lock();
	}
	
	public void unlock() {
		lock.unlock();
	}
	
	public void assertHeld() {
		assert(lock.isHeldByCurrentThread());
	}
	
	public CondVar newCondVar() {
		return new CondVar(lock.newCondition());
	}
}
