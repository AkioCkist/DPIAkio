/*
 * dpibridge — small native helpers for DPI Akio.
 *
 * Android without root cannot inject raw packets like WinDivert does on Windows,
 * so GoodbyeDPI's "Fake Request Mode" is reproduced with the zero-copy trick:
 *   1. place the fake payload in an anonymous page,
 *   2. lower the socket TTL and splice() the page into the TCP socket,
 *   3. once the segment left the host, overwrite the page with the real payload
 *      and restore the TTL.
 * The fake segment dies before reaching the server (but is seen by the DPI box);
 * the kernel later retransmits the same sequence range from the same page, which
 * now carries the real data.
 *
 * probeTtl() replaces GoodbyeDPI's SYN/ACK TTL tracking (ttltrack.c) used by
 * --auto-ttl / --min-ttl: the incoming TTL is measured with an unprivileged ICMP
 * echo (ping socket) and IP_RECVTTL / IPV6_RECVHOPLIMIT.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/ip_icmp.h>
#include <netinet/icmp6.h>
#include <linux/sockios.h>

#define EXPORT __attribute__((visibility("default")))

static int set_ttl(int fd, int ttl, int v6) {
    if (v6)
        return setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &ttl, sizeof(ttl));
    return setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl));
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* Bytes still waiting in the send queue (not yet handed to the IP layer), -1 if unknown. */
static int not_sent(int fd) {
    struct tcp_info ti;
    socklen_t l = sizeof(ti);
    memset(&ti, 0, sizeof(ti));
    if (getsockopt(fd, IPPROTO_TCP, TCP_INFO, &ti, &l) == 0 &&
        l >= offsetof(struct tcp_info, tcpi_notsent_bytes) + sizeof(ti.tcpi_notsent_bytes))
        return (int)ti.tcpi_notsent_bytes;
    int n = 0;
    if (ioctl(fd, SIOCOUTQNSD, &n) == 0) return n;
    return -1;
}

/* Wait until the socket has no unsent bytes in its send queue (data left the host). */
static void wait_sent(int fd, int max_ms) {
    long deadline = now_ms() + max_ms;
    for (;;) {
        int notsent = not_sent(fd);
        if (notsent < 0) {
            usleep(3000);
            return;
        }
        if (notsent <= 0 || now_ms() >= deadline)
            return;
        usleep(300);
    }
}

EXPORT JNIEXPORT jint JNICALL
Java_com_dpiakio_app_net_NativeBridge_sendFake(JNIEnv *env, jclass clazz, jint fd,
                                               jbyteArray fake, jbyteArray real, jint realOff,
                                               jint n, jint ttl, jint defTtl, jboolean v6) {
    (void)clazz;
    if (n <= 0) return -EINVAL;
    long pg = sysconf(_SC_PAGESIZE);
    size_t mlen = ((size_t)n + pg - 1) / pg * pg;
    char *p = mmap(NULL, mlen, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return -errno;

    jsize flen = (*env)->GetArrayLength(env, fake);
    memset(p, 0, (size_t)n);
    (*env)->GetByteArrayRegion(env, fake, 0, flen < n ? flen : n, (jbyte *)p);

    int fds[2];
    int ret;
    if (pipe(fds) < 0) {
        ret = -errno;
        munmap(p, mlen);
        return ret;
    }
    if (set_ttl(fd, ttl, v6) < 0) {
        ret = -errno;
        goto out;
    }
    struct iovec iov = { .iov_base = p, .iov_len = (size_t)n };
    ssize_t v = vmsplice(fds[1], &iov, 1, SPLICE_F_GIFT);
    if (v != n) {
        ret = v < 0 ? -errno : -EIO;
        set_ttl(fd, defTtl, v6);
        goto out;
    }
    ssize_t total = 0;
    while (total < n) {
        ssize_t s = splice(fds[0], NULL, fd, NULL, (size_t)(n - total), 0);
        if (s <= 0) {
            ret = s < 0 ? -errno : -EIO;
            set_ttl(fd, defTtl, v6);
            goto out;
        }
        total += s;
    }
    wait_sent(fd, 50);
    /* Replace page contents: the retransmission will carry the real data. */
    (*env)->GetByteArrayRegion(env, real, realOff, n, (jbyte *)p);
    set_ttl(fd, defTtl, v6);
    ret = n;
out:
    close(fds[0]);
    close(fds[1]);
    munmap(p, mlen);
    return ret;
}

EXPORT JNIEXPORT jint JNICALL
Java_com_dpiakio_app_net_NativeBridge_waitSent(JNIEnv *env, jclass clazz, jint fd, jint maxMs) {
    (void)env; (void)clazz;
    wait_sent(fd, maxMs);
    return 0;
}

EXPORT JNIEXPORT jint JNICALL
Java_com_dpiakio_app_net_NativeBridge_getSockOpt(JNIEnv *env, jclass clazz, jint fd, jint level, jint opt) {
    (void)env; (void)clazz;
    int v = 0;
    socklen_t l = sizeof(v);
    if (getsockopt(fd, level, opt, &v, &l) < 0) return -errno;
    return v;
}

/* Returns a new ICMP ping socket (to be protected from the VPN by the caller). */
EXPORT JNIEXPORT jint JNICALL
Java_com_dpiakio_app_net_NativeBridge_openPing(JNIEnv *env, jclass clazz, jboolean v6) {
    (void)env; (void)clazz;
    int s = socket(v6 ? AF_INET6 : AF_INET, SOCK_DGRAM | SOCK_CLOEXEC,
                   v6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP);
    if (s < 0) return -errno;
    int one = 1;
    if (v6) setsockopt(s, IPPROTO_IPV6, IPV6_RECVHOPLIMIT, &one, sizeof(one));
    else setsockopt(s, IPPROTO_IP, IP_RECVTTL, &one, sizeof(one));
    return s;
}

EXPORT JNIEXPORT void JNICALL
Java_com_dpiakio_app_net_NativeBridge_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd >= 0) close(fd);
}

/* Sends ICMP echo on an already opened (and protected) ping socket and returns
 * the TTL / Hop Limit of the reply, or a negative value. Always closes the socket. */
EXPORT JNIEXPORT jint JNICALL
Java_com_dpiakio_app_net_NativeBridge_probeTtl(JNIEnv *env, jclass clazz, jint s,
                                               jbyteArray addr, jint timeoutMs) {
    (void)clazz;
    jsize alen = (*env)->GetArrayLength(env, addr);
    int v6 = alen == 16;
    struct sockaddr_storage ss;
    socklen_t sl;
    memset(&ss, 0, sizeof(ss));
    if (v6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&ss;
        a->sin6_family = AF_INET6;
        (*env)->GetByteArrayRegion(env, addr, 0, 16, (jbyte *)&a->sin6_addr);
        sl = sizeof(*a);
    } else if (alen == 4) {
        struct sockaddr_in *a = (struct sockaddr_in *)&ss;
        a->sin_family = AF_INET;
        (*env)->GetByteArrayRegion(env, addr, 0, 4, (jbyte *)&a->sin_addr);
        sl = sizeof(*a);
    } else {
        close(s);
        return -EINVAL;
    }

    unsigned char pkt[16];
    memset(pkt, 0, sizeof(pkt));
    pkt[0] = v6 ? ICMP6_ECHO_REQUEST : ICMP_ECHO;
    pkt[7] = 1; /* sequence */
    memcpy(pkt + 8, "dpiakio!", 8);
    if (sendto(s, pkt, sizeof(pkt), 0, (struct sockaddr *)&ss, sl) < 0) {
        int e = errno;
        close(s);
        return -e;
    }

    long deadline = now_ms() + timeoutMs;
    int result = -ETIMEDOUT;
    for (;;) {
        long left = deadline - now_ms();
        if (left <= 0) break;
        struct pollfd pfd = { .fd = s, .events = POLLIN };
        int pr = poll(&pfd, 1, (int)left);
        if (pr <= 0) break;
        unsigned char buf[256];
        char cbuf[256];
        struct iovec iov = { .iov_base = buf, .iov_len = sizeof(buf) };
        struct msghdr mh;
        memset(&mh, 0, sizeof(mh));
        mh.msg_iov = &iov;
        mh.msg_iovlen = 1;
        mh.msg_control = cbuf;
        mh.msg_controllen = sizeof(cbuf);
        ssize_t r = recvmsg(s, &mh, 0);
        if (r < 1) continue;
        if (buf[0] != (v6 ? ICMP6_ECHO_REPLY : ICMP_ECHOREPLY)) continue;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&mh); c; c = CMSG_NXTHDR(&mh, c)) {
            if ((!v6 && c->cmsg_level == IPPROTO_IP && c->cmsg_type == IP_TTL) ||
                (v6 && c->cmsg_level == IPPROTO_IPV6 && c->cmsg_type == IPV6_HOPLIMIT)) {
                int ttl;
                memcpy(&ttl, CMSG_DATA(c), sizeof(ttl));
                result = ttl;
            }
        }
        if (result > 0) break;
    }
    close(s);
    return result;
}
