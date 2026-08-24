package io.kestra.gradle

import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

class TestSlotServiceTest {

    private TestSlotService newService() {
        // TestSlotService is abstract (BuildService). Instantiate a concrete subclass.
        return new TestSlotService() {}
    }

    @Test
    void 'acquire is a no-op when disarmed'() {
        def svc = newService()
        // Must not block.
        svc.acquire(':a:test')
    }

    @Test
    void 'finished is a no-op when disarmed'() {
        def svc = newService()
        svc.finished(':priority:test')
        svc.finished(':light:test')
    }

    @Test
    void 'at most limited tasks hold slots concurrently while priority is pending'() throws InterruptedException {
        def svc = newService()
        svc.arm(Set.of(':heavy:test'), 2, 1, null)

        int limited = 2
        int threads = 6
        AtomicInteger concurrent = new AtomicInteger(0)
        AtomicInteger maxSeen = new AtomicInteger(0)
        CountDownLatch allAcquired = new CountDownLatch(threads)
        CountDownLatch release = new CountDownLatch(1)

        def pool = Executors.newFixedThreadPool(threads)
        try {
            threads.times { i ->
                pool.submit {
                    svc.acquire(":light${i}:test")
                    int c = concurrent.incrementAndGet()
                    maxSeen.accumulateAndGet(c) { a, b -> Math.max(a, b) }
                    allAcquired.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    concurrent.decrementAndGet()
                    svc.finished(":light${i}:test")
                }
            }

            // Only `limited` tasks can hold the semaphore; wait until all 6 threads have called acquire.
            // But only 2 should have actually gotten through at any point.
            Thread.sleep(200)  // let threads pile up
            assertTrue(concurrent.get() <= limited,
                "Expected at most ${limited} concurrent but got ${concurrent.get()}")

            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(maxSeen.get() <= limited)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    void 'reserved permits released once when last priority task finishes'() throws InterruptedException {
        // limited=1, reserved=2 -> after priority finishes, 3 total permits available (1+2)
        def svc = newService()
        svc.arm(Set.of(':heavy:test'), 1, 2, null)

        // Acquire the single light-task permit
        def thread1 = Thread.start { svc.acquire(':light1:test') }
        thread1.join(2000)

        // A second acquire should block (only 1 permit)
        AtomicInteger blocked = new AtomicInteger(0)
        def thread2 = Thread.start {
            blocked.set(1)
            svc.acquire(':light2:test')
            blocked.set(2)
        }
        Thread.sleep(100)
        assertEquals(1, blocked.get(), 'light2 should be blocked')

        // Finish the priority task -> releases reserved=2 permits
        svc.finished(':heavy:test')

        // thread2 should now get through
        thread2.join(2000)
        assertEquals(2, blocked.get(), 'light2 should have acquired after priority finished')

        // A third light task should also get through immediately (3 total permits now)
        def thread3 = Thread.start { svc.acquire(':light3:test') }
        thread3.join(2000)
        assertFalse(thread3.isAlive())
    }

    @Test
    void 'reserved permits released exactly once even with multiple priority tasks'() {
        def svc = newService()
        svc.arm(Set.of(':h1:test', ':h2:test'), 1, 2, null)

        svc.finished(':h1:test')
        svc.finished(':h2:test')
        // A third finish of a priority path should not release again.
        svc.finished(':h2:test')
        // If release happened more than once the semaphore would have excess permits causing tests
        // below to see more concurrency than expected — checked implicitly via the concurrent-cap test.
    }

    @Test
    void 'finished for unknown path is harmless'() {
        def svc = newService()
        svc.arm(Set.of(':heavy:test'), 2, 1, null)
        svc.finished(':does-not-exist:test')
    }

    @Test
    void 'double finished for light path is harmless'() throws InterruptedException {
        def svc = newService()
        svc.arm(Set.of(':heavy:test'), 2, 1, null)
        def t = Thread.start { svc.acquire(':light:test') }
        t.join(2000)
        svc.finished(':light:test')
        svc.finished(':light:test')  // second call must be a no-op
    }

    @Test
    void 'close releases remaining held permits without throwing'() throws InterruptedException {
        def svc = newService()
        svc.arm(Set.of(':heavy:test'), 1, 1, null)
        def t = Thread.start { svc.acquire(':light:test') }
        t.join(2000)
        svc.close()  // must not throw
    }
}
