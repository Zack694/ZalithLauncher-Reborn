//
// Created by maks on 19.06.2023.
//

#define _GNU_SOURCE // we are GNU GPLv3

#include <linux/limits.h>
#include <unistd.h>
#include <stdio.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sched.h>
#include <string.h>

#define FREQ_MAX 256
#define MAX_CORES 16

void bigcore_format_cpu_path(char* buffer, unsigned int cpu_core) {
    snprintf(buffer, PATH_MAX, "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", cpu_core);
}

void bigcore_set_affinity() {
    char path_buffer[PATH_MAX];
    char freq_buffer[FREQ_MAX];
    char* discard;
    unsigned long core_freqs[MAX_CORES];
    unsigned long max_freq = 0;
    unsigned long min_freq = ~0UL;
    unsigned int corecnt = 0;

    while (corecnt < MAX_CORES) {
        bigcore_format_cpu_path(path_buffer, corecnt);
        int corefreqfd = open(path_buffer, O_RDONLY);
        if (corefreqfd != -1) {
            ssize_t read_count = read(corefreqfd, freq_buffer, FREQ_MAX - 1);
            close(corefreqfd);
            if (read_count > 0) {
                freq_buffer[read_count] = 0;
                unsigned long freq = strtoul(freq_buffer, &discard, 10);
                core_freqs[corecnt] = freq;
                if (freq > max_freq) max_freq = freq;
                if (freq > 0 && freq < min_freq) min_freq = freq;
                corecnt++;
                continue;
            }
        }
        break;
    }

    if (corecnt == 0) {
        return;
    }

    cpu_set_t bigcore_affinity_set;
    CPU_ZERO(&bigcore_affinity_set);
    unsigned int pinned_count = 0;

    // On big.LITTLE SoCs (max_freq > min_freq), include ALL performance + prime cores
    // (every core faster than the LITTLE efficiency cluster) so the Render thread and its
    // driver workers aren't bottlenecked onto a single core.
    for (unsigned int i = 0; i < corecnt; i++) {
        if (max_freq > min_freq) {
            if (core_freqs[i] > min_freq) {
                CPU_SET_S(i, CPU_SETSIZE, &bigcore_affinity_set);
                pinned_count++;
            }
        } else {
            CPU_SET_S(i, CPU_SETSIZE, &bigcore_affinity_set);
            pinned_count++;
        }
    }

    int result = sched_setaffinity(0, CPU_SETSIZE, &bigcore_affinity_set);
    if (result != 0) {
        printf("bigcore: setting affinity failed: %s\n", strerror(result));
    } else {
        printf("bigcore: pinned current thread (TID %d) across %u performance core(s) (max %lu KHz)\n",
               gettid(), pinned_count, max_freq);
    }
}
