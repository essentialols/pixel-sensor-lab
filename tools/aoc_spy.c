/*
 * aoc_spy.c — Intercept AOC IPC messages for raw spectral data
 *
 * This tool uses a BPF kprobe on the AOC IPC reception function
 * to dump raw sensor buffers before they are processed by the HAL.
 *
 * Target: aoc_ipc_receive (or similar in aoc_core.ko)
 * 
 * Build: clang -O2 -target bpf -c aoc_spy.c -o aoc_spy.o
 */

#include <linux/bpf.h>
#include <linux/ptrace.h>
#include <bpf/bpf_helpers.h>

struct aoc_msg_header {
    uint32_t service_id;
    uint32_t msg_type;
    uint32_t payload_len;
};

/* 
 * We want to hook into the function that receives data from AOC.
 * Based on research, a good candidate is 'aoc_ipc_rx_callback' 
 * or 'aoc_ipc_core_receive'.
 */

SEC("kprobe/aoc_ipc_core_receive")
int kprobe_aoc_receive(struct pt_regs *ctx) {
    /* 
     * Arguments:
     *   arg0: struct aoc_ipc_device *
     *   arg1: struct aoc_msg_header *
     *   arg2: void *payload
     */
    struct aoc_msg_header hdr;
    bpf_probe_read_kernel(&hdr, sizeof(hdr), (void *)PT_REGS_PARM2(ctx));

    /* 
     * Filter for USF service (we need to find the actual service_id for USF)
     * For now, we dump all messages to see what's there.
     */
    
    bpf_printk("AOC MSG: svc=%u type=%u len=%u\n", 
               hdr.service_id, hdr.msg_type, hdr.payload_len);

    /* If it looks like a sensor event (service_id for USF is usually 1 or 2) */
    if (hdr.payload_len > 0 && hdr.payload_len < 512) {
        char buf[64];
        bpf_probe_read_kernel(buf, sizeof(buf), (void *)PT_REGS_PARM3(ctx));
        /* Hex dump or specific parsing could go here */
    }

    return 0;
}

char _license[] SEC("license") = "GPL";
