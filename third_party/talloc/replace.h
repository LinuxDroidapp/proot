#ifndef LINUXDROID_TALLOC_REPLACE_H
#define LINUXDROID_TALLOC_REPLACE_H

#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_GETAUXVAL 1
#define HAVE_INTPTR_T 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_VA_COPY 1

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

#ifndef HAVE_MEMSET_EXPLICIT
static inline void *rep_memset_explicit(void *block, int c, size_t size)
{
    void *ptr = memset(block, c, size);
    __asm__ volatile("" : : "g"(block) : "memory");
    return ptr;
}
#define memset_explicit rep_memset_explicit
#endif

#endif
