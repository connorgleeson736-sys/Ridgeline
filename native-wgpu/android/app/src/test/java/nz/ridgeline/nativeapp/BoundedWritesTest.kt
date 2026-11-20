package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class BoundedWritesTest {
    @Test fun burstsWithinTheByteBudgetAreAllWrittenInOrder(){
        val writes=BoundedWrites("test",1000);val gate=CountDownLatch(1);val done=CountDownLatch(41);val order=CopyOnWriteArrayList<Int>()
        try{
            writes.submit(1){gate.await(2,TimeUnit.SECONDS);done.countDown()}
            // Forty small writes: an eight-slot queue dropped thirty-two of these.
            for(i in 0 until 40)assertTrue(writes.submit(20){order.add(i);done.countDown()})
            gate.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
            assertEquals((0 until 40).toList(),order.toList());assertEquals(0L,writes.stats()["dropped"])
        }finally{writes.close()}
    }
    @Test fun overBudgetDropsTheOldestPendingWritesAndRefusesOversizedOnes(){
        val writes=BoundedWrites("test",100);val gate=CountDownLatch(1);val ran=CopyOnWriteArrayList<String>();val done=CountDownLatch(1)
        try{
            writes.submit(1){gate.await(2,TimeUnit.SECONDS)}
            Thread.sleep(50)
            writes.submit(60){ran.add("old")};writes.submit(60){ran.add("new");done.countDown()}
            assertFalse(writes.submit(101){ran.add("huge")})
            gate.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
            assertEquals(listOf("new"),ran.toList());assertEquals(2L,writes.stats()["dropped"])
        }finally{writes.close()}
    }
    @Test fun drainFinishesPendingWritesButRefusesNewOnes(){
        val writes=BoundedWrites("test",1000);val gate=CountDownLatch(1);val done=CountDownLatch(1)
        writes.submit(1){gate.await(2,TimeUnit.SECONDS)};writes.submit(10){done.countDown()}
        writes.drain();assertFalse(writes.submit(10){});assertTrue(writes.isShutdown)
        gate.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
    }
}
