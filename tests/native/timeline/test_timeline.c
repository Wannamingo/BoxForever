/* Production timeline.c with a controllable asynchronous Vulkan driver.
 * Each CTest case runs in a fresh process, like an Android game activity. */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <vulkan/vulkan.h>
#include "../../../hw/xbox/hle/vk/present_wait.h"

#ifdef _WIN32
#include <windows.h>
typedef struct { CRITICAL_SECTION m; } HleMutex;
typedef struct { CONDITION_VARIABLE c; } HleCond;
typedef struct { HANDLE t; } HleThread;
static void hle_mutex_init(HleMutex *m) { InitializeCriticalSection(&m->m); }
static void hle_mutex_lock(HleMutex *m) { EnterCriticalSection(&m->m); }
static void hle_mutex_unlock(HleMutex *m) { LeaveCriticalSection(&m->m); }
static void hle_cond_init(HleCond *c) { InitializeConditionVariable(&c->c); }
static void hle_cond_wait(HleCond *c, HleMutex *m) { SleepConditionVariableCS(&c->c, &m->m, INFINITE); }
static void hle_cond_broadcast(HleCond *c) { WakeAllConditionVariable(&c->c); }
static void sleep_ms(unsigned n) { Sleep(n); }
static int64_t hle_now_ns(void) { return (int64_t)GetTickCount64() * 1000000; }
static int test_nanosleep(const struct timespec *t, struct timespec *unused) {
    sleep_ms((unsigned)(t->tv_sec * 1000 + (t->tv_nsec + 999999) / 1000000)); return 0;
}
#define nanosleep test_nanosleep
static uint64_t atom_read(uint64_t *p) { return (uint64_t)InterlockedCompareExchange64((LONG64 *)p, 0, 0); }
static void atom_set(uint64_t *p, uint64_t v) { InterlockedExchange64((LONG64 *)p, (LONG64)v); }
static uint64_t atom_cmp(uint64_t *p, uint64_t a, uint64_t b) {
    return (uint64_t)InterlockedCompareExchange64((LONG64 *)p, (LONG64)b, (LONG64)a);
}
typedef struct { void *(*fn)(void *); void *arg; } Start;
static DWORD WINAPI start_thread(void *p) {
    Start s = *(Start *)p; free(p); s.fn(s.arg); return 0;
}
static void hle_thread_create(HleThread *t, const char *name, void *(*fn)(void *), void *arg, int mode) {
    Start *s = malloc(sizeof(*s)); assert(s); *s = (Start){fn, arg};
    t->t = CreateThread(NULL, 0, start_thread, s, 0, NULL); assert(t->t);
}
static void join(HleThread *t) { assert(WaitForSingleObject(t->t, 5000) == WAIT_OBJECT_0); CloseHandle(t->t); }
#else
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
typedef struct { pthread_mutex_t m; } HleMutex;
typedef struct { pthread_cond_t c; } HleCond;
typedef struct { pthread_t t; } HleThread;
static void hle_mutex_init(HleMutex *m) { pthread_mutex_init(&m->m, NULL); }
static void hle_mutex_lock(HleMutex *m) { pthread_mutex_lock(&m->m); }
static void hle_mutex_unlock(HleMutex *m) { pthread_mutex_unlock(&m->m); }
static void hle_cond_init(HleCond *c) { pthread_cond_init(&c->c, NULL); }
static void hle_cond_wait(HleCond *c, HleMutex *m) { pthread_cond_wait(&c->c, &m->m); }
static void hle_cond_broadcast(HleCond *c) { pthread_cond_broadcast(&c->c); }
static void sleep_ms(unsigned n) { struct timespec t = {n / 1000, (n % 1000) * 1000000L}; nanosleep(&t, NULL); }
static int64_t hle_now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec; }
static uint64_t atom_read(uint64_t *p) { return __atomic_load_n(p, __ATOMIC_SEQ_CST); }
static void atom_set(uint64_t *p, uint64_t v) { __atomic_store_n(p, v, __ATOMIC_SEQ_CST); }
static uint64_t atom_cmp(uint64_t *p, uint64_t a, uint64_t b) {
    __atomic_compare_exchange_n(p, &a, b, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); return a;
}
static void hle_thread_create(HleThread *t, const char *name, void *(*fn)(void *), void *arg, int mode) {
    assert(pthread_create(&t->t, NULL, fn, arg) == 0);
}
static void join(HleThread *t) { pthread_join(t->t, NULL); }
#endif

#define hle_atomic_read(p) (sizeof(*(p)) == 8 ? atom_read((uint64_t *)(p)) : *(p))
#define hle_atomic_set(p, v) do { if (sizeof(*(p)) == 8) atom_set((uint64_t *)(p), (v)); else *(p) = (v); } while (0)
#define hle_atomic_cmpxchg(p, a, b) atom_cmp(p, a, b)
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define MAX(a,b) ((a) > (b) ? (a) : (b))
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#define likely(x) (x)
#define HLE_THREAD_DETACHED 0
#define HLE_ACT_GPU 0
#define HLE_QUIRK_NO_SEM_WAIT 1
static bool poll_semaphore;
static bool hle_quirk(int flag) { assert(flag == HLE_QUIRK_NO_SEM_WAIT); return poll_semaphore; }
static void hle_log(const char *tag, const char *fmt, ...) {}
static uint32_t hle_hitch_enter(int n) { return 0; }
static void hle_hitch_leave(uint32_t n) {}
typedef struct { HleMutex m; HleCond c; bool set; } HleEvent;
static uint64_t idle_wait_entered, inject_loss_at_reset;
static void lose_at_reset(void);
static void hle_event_init(HleEvent *e, bool set) { hle_mutex_init(&e->m); hle_cond_init(&e->c); e->set = set; }
static void hle_event_set(HleEvent *e) { hle_mutex_lock(&e->m); e->set = true; hle_cond_broadcast(&e->c); hle_mutex_unlock(&e->m); }
static void hle_event_reset(HleEvent *e) {
    if (atom_read(&inject_loss_at_reset)) lose_at_reset();
    hle_mutex_lock(&e->m); e->set = false; hle_mutex_unlock(&e->m);
}
static void hle_event_wait(HleEvent *e) {
    hle_mutex_lock(&e->m);
    atom_set(&idle_wait_entered, 1);
    while (!e->set) hle_cond_wait(&e->c, &e->m);
    hle_mutex_unlock(&e->m);
}

static struct {
    VkDevice dev; VkQueue queue;
    struct { bool timeline; } caps;
    VkPhysicalDeviceProperties props;
    struct {
        PFN_vkCreateSemaphore vkCreateSemaphore;
        PFN_vkWaitSemaphores vkWaitSemaphores;
        PFN_vkGetSemaphoreCounterValue vkGetSemaphoreCounterValue;
        PFN_vkCreateFence vkCreateFence;
        PFN_vkDestroyFence vkDestroyFence;
        PFN_vkResetFences vkResetFences;
        PFN_vkWaitForFences vkWaitForFences;
        PFN_vkQueueSubmit2 vkQueueSubmit2;
        PFN_vkInvalidateMappedMemoryRanges vkInvalidateMappedMemoryRanges;
    } t;
} g_hvk;
typedef struct { VkBuffer buf; VkDeviceMemory mem; uint8_t *map; struct { uint32_t size; } alloc; } Arena;
static Arena g_hland, g_harena;
static bool g_hland_coherent;
static uint8_t guest[16], staging[16];
static uint8_t *hle_pa_ptr(uint32_t pa, uint32_t len) { assert(pa + len <= sizeof(guest)); return guest + pa; }
static HleMutex queue_lock;
static uint64_t lost, allowed, serial, callback_value, waiter_done, wait_ok, producer_done, entered;
static void hle_vk_queue_lock(void) { hle_mutex_lock(&queue_lock); }
static void hle_vk_queue_unlock(void) { hle_mutex_unlock(&queue_lock); }
static bool hle_vk_lost(void) { return atom_read(&lost) != 0; }
void hle_tl_lose(void);
uint64_t hle_tl_completed(void);
uint64_t hle_tl_submitted(void);
bool hle_tl_wait(uint64_t value);
static void hle_vk_lose(VkResult r, const char *why) { if (!atom_cmp(&lost, 0, 1)) hle_tl_lose(); }
static void lose_at_reset(void) { hle_vk_lose(VK_ERROR_DEVICE_LOST, "before event reset"); }

#include "../../../hw/xbox/hle/vk/timeline.c"

static struct MockFence { uint64_t serial, busy; } fences[FENCE_SLOTS];
static unsigned created, destroyed;
static bool fail_create, fail_submit, fail_reset;
static VkResult VKAPI_CALL create_fence(VkDevice d, const VkFenceCreateInfo *i, const VkAllocationCallbacks *a, VkFence *f) {
    if (fail_create && created == 2) return VK_ERROR_OUT_OF_HOST_MEMORY;
    assert(created < FENCE_SLOTS); *f = (VkFence)&fences[created++]; return VK_SUCCESS;
}
static void VKAPI_CALL destroy_fence(VkDevice d, VkFence f, const VkAllocationCallbacks *a) { destroyed++; }
static VkResult VKAPI_CALL reset_fences(VkDevice d, uint32_t n, const VkFence *f) {
    assert(n == 1); if (fail_reset) return VK_ERROR_DEVICE_LOST;
    assert(!atom_read(&((struct MockFence *)*f)->busy)); return VK_SUCCESS;
}
static VkResult VKAPI_CALL wait_fences(VkDevice d, uint32_t n, const VkFence *f, VkBool32 all, uint64_t timeout) {
    struct MockFence *s = (struct MockFence *)*f;
    assert(n == 1 && all);
    if (hle_vk_lost()) return VK_ERROR_DEVICE_LOST;
    if (atom_read(&s->serial) > atom_read(&allowed)) { sleep_ms(1); return VK_TIMEOUT; }
    atom_set(&s->busy, 0); return VK_SUCCESS;
}
static VkResult VKAPI_CALL queue_submit(VkQueue q, uint32_t n, const VkSubmitInfo2 *s, VkFence f) {
    assert(n == 1);
    if (fail_submit) return VK_ERROR_DEVICE_LOST;
    uint64_t v = atom_read(&serial) + 1; atom_set(&serial, v);
    if (f) {
        assert(!g_hvk.caps.timeline && s->signalSemaphoreInfoCount == 0);
        struct MockFence *m = (struct MockFence *)f;
        atom_set(&m->serial, v); atom_set(&m->busy, 1);
    } else { assert(g_hvk.caps.timeline && s->signalSemaphoreInfoCount == 1); }
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL create_sem(VkDevice d, const VkSemaphoreCreateInfo *s, const VkAllocationCallbacks *a, VkSemaphore *sem) {
    assert(g_hvk.caps.timeline);
    assert(((const VkSemaphoreTypeCreateInfo *)s->pNext)->semaphoreType == VK_SEMAPHORE_TYPE_TIMELINE);
    *sem = (VkSemaphore)(uintptr_t)1; return VK_SUCCESS;
}
static VkResult VKAPI_CALL wait_sem(VkDevice d, const VkSemaphoreWaitInfo *w, uint64_t t) {
    if (hle_vk_lost()) return VK_ERROR_DEVICE_LOST;
    if (*w->pValues > atom_read(&allowed)) { sleep_ms(1); return VK_TIMEOUT; } return VK_SUCCESS;
}
static VkResult VKAPI_CALL counter(VkDevice d, VkSemaphore s, uint64_t *v) { *v = atom_read(&allowed); return VK_SUCCESS; }
static VkResult VKAPI_CALL invalidate_failure(VkDevice d, uint32_t n, const VkMappedMemoryRange *r) {
    return VK_ERROR_DEVICE_LOST;
}
static void completed(uint64_t v) { assert(hle_tl_completed() >= v); atom_set(&callback_value, v); }
static void until(uint64_t *p, uint64_t v) {
    for (unsigned i = 0; i < 5000 && atom_read(p) < v; i++) sleep_ms(1);
    assert(atom_read(p) >= v);
}
static VkResult submit_value(uint64_t v) {
    VkSemaphoreSubmitInfo sig = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO, .semaphore = hle_tl_sem(), .value = v};
    VkSubmitInfo2 s = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2,
        .signalSemaphoreInfoCount = g_hvk.caps.timeline ? 1 : 0, .pSignalSemaphoreInfos = &sig};
    VkResult r = hle_tl_submit(&s, v);
    if (r != VK_SUCCESS) hle_vk_lose(r, "test submit");
    if (r == VK_SUCCESS) hle_tl_signaled(v);
    return r;
}
static void *waiter(void *p) {
    atom_set(&entered, 1);
    atom_set(&wait_ok, hle_tl_wait((uint64_t)(uintptr_t)p));
    atom_set(&waiter_done, 1);
    return NULL;
}
static void *producer(void *p) { atom_set(&entered, 1); submit_value(65); atom_set(&producer_done, 1); return NULL; }

static HlePendingPresent pending;
static unsigned waits, presents;
static VkResult next_present_result;
static VkResult mock_present_wait(uint64_t value, uint64_t timeout) {
    assert(value == 12 && timeout == (waits ? 5000000ull : 1000000000ull));
    waits++;
    return waits == 1 ? VK_TIMEOUT : next_present_result;
}
static void mock_present(bool cpu, uint64_t key, uint32_t shader_gen, bool fallback) {
    assert(cpu && key == 98 && shader_gen == 4 && fallback && !pending.value);
    presents++;
}
static void test_present_retry(VkResult result) {
    next_present_result = result;
    pending = (HlePendingPresent){12, 98, 4, true};
    assert(hle_present_wait_pending(&pending, 1000000000ull, mock_present_wait, mock_present) == VK_TIMEOUT);
    assert(pending.value == 12 && !presents);
    assert(hle_present_wait_pending(&pending, 5000000ull, mock_present_wait, mock_present) == result);
    assert(!pending.value && presents == (unsigned)(result == VK_SUCCESS));
    assert(hle_present_wait_pending(&pending, 5000000ull, mock_present_wait, mock_present) == VK_NOT_READY);
    assert(waits == 2);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *test = argv[1];
    if (!strcmp(test, "present_retry") || !strcmp(test, "present_loss")) {
        test_present_retry(!strcmp(test, "present_retry") ? VK_SUCCESS : VK_ERROR_DEVICE_LOST);
        printf("PASS %s\n", test); return 0;
    }
    hle_mutex_init(&queue_lock);
    g_hvk.t.vkCreateFence = create_fence; g_hvk.t.vkDestroyFence = destroy_fence;
    g_hvk.t.vkResetFences = reset_fences; g_hvk.t.vkWaitForFences = wait_fences;
    g_hvk.t.vkQueueSubmit2 = queue_submit; g_hvk.t.vkCreateSemaphore = create_sem;
    g_hvk.t.vkWaitSemaphores = wait_sem; g_hvk.t.vkGetSemaphoreCounterValue = counter;
    g_harena.map = staging;
    if (!strcmp(test, "init_failure")) {
        fail_create = true; assert(hle_tl_init(completed) == -1); assert(destroyed == 2); return 0;
    }
    poll_semaphore = !strcmp(test, "native_poll");
    g_hvk.caps.timeline = !strcmp(test, "native") || poll_semaphore ||
        !strcmp(test, "loss_readback_timeline") || !strcmp(test, "idle_watcher") || !strcmp(test, "loss_at_reset");
    atom_set(&inject_loss_at_reset, !strcmp(test, "loss_at_reset"));
    assert(hle_tl_init(completed) == 0);
    if (!strcmp(test, "ordered") || !strcmp(test, "native") || poll_semaphore) {
        memset(staging, 0x5a, sizeof(staging));
        assert(hle_tl_land(3, 0, 0, sizeof(staging)));
        hle_tl_land_copied(0, 8, 8, 2);
        assert(submit_value(3) == VK_SUCCESS); assert(submit_value(8) == VK_SUCCESS);
        HleThread w; hle_thread_create(&w, "waiter", waiter, (void *)(uintptr_t)3, 0);
        until(&entered, 1); sleep_ms(20); assert(!atom_read(&waiter_done)); assert(hle_tl_completed() == 0);
        atom_set(&allowed, g_hvk.caps.timeline ? 3 : 1);
        join(&w); assert(atom_read(&wait_ok)); assert(!memcmp(guest, staging, sizeof(guest)));
        assert(hle_tl_completed() == 3);
        atom_set(&allowed, g_hvk.caps.timeline ? 8 : 2); assert(hle_tl_wait(8)); until(&callback_value, 8);
    } else if (!strcmp(test, "wrap")) {
        atom_set(&allowed, UINT64_MAX);
        for (uint64_t i = 1; i <= 256; i++) assert(submit_value(i * 3) == VK_SUCCESS);
        assert(hle_tl_wait(768)); until(&callback_value, 768); assert(created == FENCE_SLOTS);
    } else if (!strcmp(test, "pressure") || !strcmp(test, "loss")) {
        for (uint64_t i = 1; i <= 64; i++) assert(submit_value(i) == VK_SUCCESS);
        HleThread p, w; hle_thread_create(&p, "producer", producer, NULL, 0);
        until(&entered, 1); sleep_ms(20); assert(!atom_read(&producer_done));
        hle_thread_create(&w, "waiter", waiter, (void *)(uintptr_t)65, 0);
        if (!strcmp(test, "loss")) hle_vk_lose(VK_ERROR_DEVICE_LOST, "test loss");
        else atom_set(&allowed, UINT64_MAX);
        join(&p); join(&w);
        assert(atom_read(&wait_ok) == !!strcmp(test, "loss"));
        assert(hle_tl_completed() == (!strcmp(test, "loss") ? 0 : 65));
    } else if (!strcmp(test, "submit_failure") || !strcmp(test, "reset_failure")) {
        fail_submit = !strcmp(test, "submit_failure"); fail_reset = !strcmp(test, "reset_failure");
        assert(submit_value(1) == VK_ERROR_DEVICE_LOST); assert(g_fences.count == 0);
        assert(!hle_tl_wait(1)); assert(hle_tl_completed() == 0);
    } else if (!strncmp(test, "loss_readback_", 14)) {
        memset(staging, 0x5a, sizeof(staging));
        assert(hle_tl_land(1, 0, 0, 8));
        assert(hle_tl_land(2, 8, 8, 8));
        hle_tl_land_copied(0, 8, 8, 1);
        hle_tl_land_copied(8, 8, 8, 1);
        assert(submit_value(1) == VK_SUCCESS); assert(submit_value(2) == VK_SUCCESS);
        atom_set(&allowed, 1);
        assert(hle_tl_wait(1)); until(&callback_value, 1);
        HleThread w; hle_thread_create(&w, "waiter", waiter, (void *)(uintptr_t)2, 0);
        until(&entered, 1);
        hle_vk_lose(VK_ERROR_DEVICE_LOST, "readback interrupted");
        join(&w);
        assert(!atom_read(&wait_ok)); assert(hle_tl_completed() == 1);
        assert(!memcmp(guest, staging, 8));
        for (unsigned i = 8; i < sizeof(guest); i++) assert(guest[i] == 0);
        assert(!hle_tl_land(3, 0, 0, 8));
    } else if (!strcmp(test, "invalidate_failure")) {
        g_hland.buf = (VkBuffer)(uintptr_t)1;
        g_hland.map = staging; g_hland.alloc.size = sizeof(staging);
        g_hvk.t.vkInvalidateMappedMemoryRanges = invalidate_failure;
        memset(staging, 0x5a, sizeof(staging));
        assert(hle_tl_land(1, 0, 0, 8)); hle_tl_land_copied(0, 8, 8, 1);
        assert(submit_value(1) == VK_SUCCESS); atom_set(&allowed, 1);
        assert(!hle_tl_wait(1)); assert(hle_tl_completed() == 0);
        for (unsigned i = 0; i < sizeof(guest); i++) assert(guest[i] == 0);
    } else if (!strcmp(test, "idle_watcher")) {
        until(&idle_wait_entered, 1);
        hle_vk_lose(VK_ERROR_DEVICE_LOST, "idle watcher");
        assert(!hle_tl_wait(1));
    } else if (!strcmp(test, "loss_at_reset")) {
        until(&lost, 1);
        assert(!hle_tl_wait(1));
    } else { assert(false); }
    hle_vk_lose(VK_ERROR_DEVICE_LOST, "test shutdown"); join(&g_tl.thread);
    printf("PASS %s\n", test); return 0;
}
