/* Standalone regression for the native vCPU's idle wake conditions.
 * cc -D_GNU_SOURCE -pthread -I. tests/native/xc/irq_idle_test.c native/core/irq.c -o irq_idle_test
 */
#include "native/core/irq.h"

#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

void nc_cpu_request_interrupt(NcCpu *cpu) { (void)cpu; }
void nc_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }

#define WAIT_LIMIT_NS 1000000000ull

static unsigned read_status;

static void *reader(void *arg)
{
    const uint64_t end = nc_irq_now_ns() + WAIT_LIMIT_NS;
    while (!__atomic_load_n(&g_nc_idle_waiting, __ATOMIC_ACQUIRE)) {
        assert(nc_irq_now_ns() < end);
        sched_yield();
    }
    if (arg) {
        const struct timespec delay = {0, 5000000};
        nanosleep(&delay, NULL);
    }
    read_status = 0x55aau;
    nc_irq_post_work();
    return NULL;
}

int main(void)
{
    NcIrqIdleTrace trace;
    nc_irq_enable(1, true);
    nc_irq_set(1, true);
    assert(nc_irq_idle_wait_trace(0, WAIT_LIMIT_NS, &trace) == (1u << 1));
    assert(!strcmp(trace.reason, "irq") && trace.wait_result == 0);
    nc_irq_set(1, false);

    nc_irq_post_work();
    assert(nc_irq_idle_wait_trace(0, WAIT_LIMIT_NS, &trace) == 0);
    assert(!strcmp(trace.reason, "host-work") && trace.wait_result == 0);
    assert(nc_irq_take_work() && !nc_irq_take_work());

    for (unsigned i = 0; i < 32; i++) {
        pthread_t thread;
        read_status = 0;
        assert(pthread_create(&thread, NULL, reader, i % 2 ? &trace : NULL) == 0);
        nc_irq_idle_wait_trace(0, WAIT_LIMIT_NS, &trace);
        assert(!strcmp(trace.reason, "host-work") && trace.work && trace.wait_result == 0);
        assert(nc_irq_take_work() && read_status == 0x55aau);
        assert(pthread_join(thread, NULL) == 0);
    }

    const uint64_t deadline = nc_irq_now_ns() + 10000000ull;
    nc_irq_idle_wait_trace(deadline, WAIT_LIMIT_NS, &trace);
    assert(!strcmp(trace.reason, "deadline") && trace.wait_result == ETIMEDOUT);
    assert(trace.start_ns + trace.elapsed_ns >= deadline && !trace.lines && !trace.work);
    puts("PASS irq_idle: posted work, sleep transition, I/O completion notification and deadline");
}
