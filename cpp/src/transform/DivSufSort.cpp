/*
Copyright 2011-2017 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

#include <stddef.h>
#include "DivSufSort.hpp"

using namespace kanzi;

const int DivSufSort::SQQ_TABLE[] = {
    0, 16, 22, 27, 32, 35, 39, 42, 45, 48, 50, 53, 55, 57, 59, 61,
    64, 65, 67, 69, 71, 73, 75, 76, 78, 80, 81, 83, 84, 86, 87, 89,
    90, 91, 93, 94, 96, 97, 98, 99, 101, 102, 103, 104, 106, 107, 108, 109,
    110, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126,
    128, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142,
    143, 144, 144, 145, 146, 147, 148, 149, 150, 150, 151, 152, 153, 154, 155, 155,
    156, 157, 158, 159, 160, 160, 161, 162, 163, 163, 164, 165, 166, 167, 167, 168,
    169, 170, 170, 171, 172, 173, 173, 174, 175, 176, 176, 177, 178, 178, 179, 180,
    181, 181, 182, 183, 183, 184, 185, 185, 186, 187, 187, 188, 189, 189, 190, 191,
    192, 192, 193, 193, 194, 195, 195, 196, 197, 197, 198, 199, 199, 200, 201, 201,
    202, 203, 203, 204, 204, 205, 206, 206, 207, 208, 208, 209, 209, 210, 211, 211,
    212, 212, 213, 214, 214, 215, 215, 216, 217, 217, 218, 218, 219, 219, 220, 221,
    221, 222, 222, 223, 224, 224, 225, 225, 226, 226, 227, 227, 228, 229, 229, 230,
    230, 231, 231, 232, 232, 233, 234, 234, 235, 235, 236, 236, 237, 237, 238, 238,
    239, 240, 240, 241, 241, 242, 242, 243, 243, 244, 244, 245, 245, 246, 246, 247,
    247, 248, 248, 249, 249, 250, 250, 251, 251, 252, 252, 253, 253, 254, 254, 255
};

const int DivSufSort::LOG_TABLE[] = {
    -1, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
    5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
    6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
    6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
};

DivSufSort::DivSufSort()
{
    _length = 0;
    _buffer = new short[0];
    _ssStack = new Stack(SS_MISORT_STACKSIZE);
    _trStack = new Stack(TR_STACKSIZE);
    _mergeStack = new Stack(SS_SMERGE_STACKSIZE);
}

DivSufSort::~DivSufSort()
{
    delete[] _buffer;
    delete _ssStack;
    delete _trStack;
    delete _mergeStack;
}

void DivSufSort::reset()
{
    _ssStack->_index = 0;
    _trStack->_index = 0;
    _mergeStack->_index = 0;

    for (int i = 255; i >= 0; i--)
        _bucketA[i] = 0;

    for (int i = 65535; i >= 0; i--)
        _bucketB[i] = 0;
}

void DivSufSort::computeSuffixArray(byte input[], int sa[], int start, int length)
{
    // Lazy dynamic memory allocation
    if (_length < length + 1) {
        delete[] _buffer;
        _buffer = new short[length + 1];
    }

    for (int i = 0; i < length; i++)
        _buffer[i] = (short)(input[start + i] & 0xFF);

    _sa = sa;
    reset();
    int m = sortTypeBstar(_bucketA, _bucketB, length);
    constructSuffixArray(_bucketA, _bucketB, length, m);
}

void DivSufSort::constructSuffixArray(int bucket_A[], int bucket_B[], int n, int m)
{
    if (m > 0) {
        for (int c1 = 254; c1 >= 0; c1--) {
            const int idx = c1 << 8;
            const int i = bucket_B[idx + c1 + 1];
            int k = 0;
            int c2 = -1;

            for (int j = bucket_A[c1 + 1] - 1; j >= i; j--) {
                int s = _sa[j];
                _sa[j] = ~s;

                if (s <= 0)
                    continue;

                s--;
                const int c0 = _buffer[s];

                if ((s > 0) && (_buffer[s - 1] > c0))
                    s = ~s;

                if (c0 != c2) {
                    if (c2 >= 0)
                        bucket_B[idx + c2] = k;

                    c2 = c0;
                    k = bucket_B[idx + c2];
                }

                _sa[k--] = s;
            }
        }
    }

    int c2 = _buffer[n - 1];
    int k = bucket_A[c2];
    _sa[k++] = (_buffer[n - 2] < c2) ? ~(n - 1) : (n - 1);

    // Scan the suffix array from left to right.
    for (int i = 0; i < n; i++) {
        int s = _sa[i];

        if (s <= 0) {
            _sa[i] = ~s;
            continue;
        }

        s--;
        const int c0 = _buffer[s];

        if ((s == 0) || (_buffer[s - 1] < c0))
            s = ~s;

        if (c0 != c2) {
            bucket_A[c2] = k;
            c2 = c0;
            k = bucket_A[c2];
        }

        _sa[k++] = s;
    }
}

int DivSufSort::computeBWT(byte input[], int sa[], int start, int length)
{
    // Lazy dynamic memory allocation
    if (_length < length + 1) {
        delete[] _buffer;
        _buffer = new short[length + 1];
    }

    for (int i = 0; i < length; i++)
        _buffer[i] = (short)(input[start + i] & 0xFF);

    _sa = sa;
    reset();
    int m = sortTypeBstar(_bucketA, _bucketB, length);
    return constructBWT(_bucketA, _bucketB, length, m);
}

int DivSufSort::constructBWT(int bucket_A[], int bucket_B[], int n, int m)
{
    int pIdx = -1;

    if (m > 0) {
        for (int c1 = 254; c1 >= 0; c1--) {
            const int idx = c1 << 8;
            const int i = bucket_B[idx + c1 + 1];
            int k = 0;
            int c2 = -1;

            for (int j = bucket_A[c1 + 1] - 1; j >= i; j--) {
                int s = _sa[j];

                if (s <= 0) {
                    if (s != 0)
                        _sa[j] = ~s;

                    continue;
                }

                s--;
                const int c0 = _buffer[s];
                _sa[j] = ~c0;

                if ((s > 0) && (_buffer[s - 1] > c0))
                    s = ~s;

                if (c0 != c2) {
                    if (c2 >= 0)
                        bucket_B[idx + c2] = k;

                    c2 = c0;
                    k = bucket_B[idx + c2];
                }

                _sa[k--] = s;
            }
        }
    }

    int c2 = _buffer[n - 1];
    int k = bucket_A[c2];
    _sa[k++] = (_buffer[n - 2] < c2) ? ~_buffer[n - 2] : (n - 1);

    // Scan the suffix array from left to right.
    for (int i = 0; i < n; i++) {
        int s = _sa[i];

        if (s <= 0) {
            if (s != 0)
                _sa[i] = ~s;
            else
                pIdx = i;

            continue;
        }

        s--;
        const int c0 = _buffer[s];
        _sa[i] = c0;

        if ((s > 0) && (_buffer[s - 1] < c0))
            s = ~_buffer[s - 1];

        if (c0 != c2) {
            bucket_A[c2] = k;
            c2 = c0;
            k = bucket_A[c2];
        }

        _sa[k++] = s;
    }

    return pIdx;
}

int DivSufSort::sortTypeBstar(int bucket_A[], int bucket_B[], int n)
{
    int m = n;
    int c0 = _buffer[n - 1];

    // Count the number of occurrences of the first one or two characters of each
    // type A, B and B* suffix. Moreover, store the beginning position of all
    // type B* suffixes into the array SA.
    for (int i = n - 1; i >= 0;) {
        int c1;

        do {
            c1 = c0;
            bucket_A[c1]++;
            i--;
        } while ((i >= 0) && ((c0 = _buffer[i]) >= c1));

        if (i < 0)
            break;

        bucket_B[(c0 << 8) + c1]++;
        m--;
        _sa[m] = i;
        i--;
        c1 = c0;

        while ((i >= 0) && ((c0 = _buffer[i]) <= c1)) {
            bucket_B[(c1 << 8) + c0]++;
            c1 = c0;
            i--;
        }
    }

    m = n - m;
    c0 = 0;

    // A type B* suffix is lexicographically smaller than a type B suffix that
    // begins with the same first two characters.

    // Calculate the index of start/end point of each bucket.
    for (int i = 0, j = 0; c0 < 256; c0++) {
        const int t = i + bucket_A[c0];
        bucket_A[c0] = i + j; // start point
        const int idx = c0 << 8;
        i = t + bucket_B[idx + c0];

        for (int c1 = c0 + 1; c1 < 256; c1++) {
            j += bucket_B[idx + c1];
            bucket_B[idx + c1] = j; // end point
            i += bucket_B[(c1 << 8) + c0];
        }
    }

    if (m > 0) {
        // Sort the type B* suffixes by their first two characters.
        const int pab = n - m;

        for (int i = m - 2; i >= 0; i--) {
            const int t = _sa[pab + i];
            const int idx = (_buffer[t] << 8) + _buffer[t + 1];
            bucket_B[idx]--;
            _sa[bucket_B[idx]] = i;
        }

        const int t = _sa[pab + m - 1];
        c0 = (_buffer[t] << 8) + _buffer[t + 1];
        bucket_B[c0]--;
        _sa[bucket_B[c0]] = m - 1;

        // Sort the type B* substrings using ssSort.
        const int bufSize = n - m - m;
        c0 = 254;

        for (int j = m; j > 0; c0--) {
            const int idx = c0 << 8;

            for (int c1 = 255; c1 > c0; c1--) {
                const int i = bucket_B[idx + c1];

                if (j > i + 1)
                    ssSort(pab, i, j, m, bufSize, 2, n, _sa[i] == m - 1);

                j = i;
            }
        }

        // Compute ranks of type B* substrings.
        for (int i = m - 1; i >= 0; i--) {
            if (_sa[i] >= 0) {
                const int j = i;

                do {
                    _sa[m + _sa[i]] = i;
                    i--;
                } while ((i >= 0) && (_sa[i] >= 0));

                _sa[i + 1] = i - j;

                if (i <= 0)
                    break;
            }

            const int j = i;

            do {
                _sa[i] = ~_sa[i];
                _sa[m + _sa[i]] = j;
                i--;
            } while (_sa[i] < 0);

            _sa[m + _sa[i]] = j;
        }

        // Construct the inverse suffix array of type B* suffixes using trSort.
        trSort(m, 1);

        // Set the sorted order of type B* suffixes.
        c0 = _buffer[n - 1];

        for (int i = n - 1, j = m; i >= 0;) {
            i--;

            for (int c1 = c0; (i >= 0) && ((c0 = _buffer[i]) >= c1); i--) {
                c1 = c0;
            }

            if (i >= 0) {
                const int tt = i;
                i--;

                for (int c1 = c0; (i >= 0) && ((c0 = _buffer[i]) <= c1); i--) {
                    c1 = c0;
                }

                j--;
                _sa[_sa[m + j]] = ((tt == 0) || (tt - i > 1)) ? tt : ~tt;
            }
        }

        // Calculate the index of start/end point of each bucket.
        bucket_B[65535] = n; // end
        int k = m - 1;

        for (c0 = 254; c0 >= 0; c0--) {
            int i = bucket_A[c0 + 1] - 1;
            const int idx = c0 << 8;

            for (int c1 = 255; c1 > c0; c1--) {
                const int tt = i - bucket_B[(c1 << 8) + c0];
                bucket_B[(c1 << 8) + c0] = i; // end point
                i = tt;

                // Move all type B* suffixes to the correct position.
                // Typically very small number of copies, no need for arraycopy
                for (int j = bucket_B[idx + c1]; j <= k; i--, k--)
                    _sa[i] = _sa[k];
            }

            bucket_B[idx + c0 + 1] = i - bucket_B[idx + c0] + 1; // start point
            bucket_B[idx + c0] = i; // end point
        }
    }

    return m;
}

// Sub String Sort
void DivSufSort::ssSort(const int pa, int first, int last, int buf, int bufSize,
    int depth, int n, bool lastSuffix)
{
    if (lastSuffix == true)
        first++;

    int limit = 0;
    int middle = last;

    if ((bufSize < SS_BLOCKSIZE) && (bufSize < last - first)) {
        limit = ssIsqrt(last - first);

        if (bufSize < limit) {
            if (limit > SS_BLOCKSIZE)
                limit = SS_BLOCKSIZE;

            middle = last - limit;
            buf = middle;
            bufSize = limit;
        }
        else {
            limit = 0;
        }
    }

    int a;
    int i = 0;

    for (a = first; middle - a > SS_BLOCKSIZE; a += SS_BLOCKSIZE, i++) {
        ssMultiKeyIntroSort(pa, a, a + SS_BLOCKSIZE, depth);
        int curBufSize = last - (a + SS_BLOCKSIZE);
        int curBuf;

        if (curBufSize > bufSize) {
            curBuf = a + SS_BLOCKSIZE;
        }
        else {
            curBufSize = bufSize;
            curBuf = buf;
        }

        int k = SS_BLOCKSIZE;
        int b = a;

        for (int j = i; (j & 1) != 0; j >>= 1) {
            ssSwapMerge(pa, b - k, b, b + k, curBuf, curBufSize, depth);
            b -= k;
            k <<= 1;
        }
    }

    ssMultiKeyIntroSort(pa, a, middle, depth);

    for (int k = SS_BLOCKSIZE; i != 0; k <<= 1, i >>= 1) {
        if ((i & 1) == 0)
            continue;

        ssSwapMerge(pa, a - k, a, middle, buf, bufSize, depth);
        a -= k;
    }

    if (limit != 0) {
        ssMultiKeyIntroSort(pa, middle, last, depth);
        ssInplaceMerge(pa, first, middle, last, depth);
    }

    if (lastSuffix == true) {
        i = _sa[first - 1];
        const int p1 = _sa[pa + i];
        const int p11 = n - 2;

        for (a = first; (a < last) && ((_sa[a] < 0) || (ssCompare(p1, p11, pa + _sa[a], depth) > 0)); a++)
            _sa[a - 1] = _sa[a];

        _sa[a - 1] = i;
    }
}

int DivSufSort::ssCompare(int pa, int pb, int p2, int depth)
{
    int u1 = depth + pa;
    int u2 = depth + _sa[p2];
    const int u1n = pb + 2;
    const int u2n = _sa[p2 + 1] + 2;

    if (u1n - u1 > u2n - u2) {
        while ((u2 < u2n) && (_buffer[u1] == _buffer[u2])) {
            u1++;
            u2++;
        }
    }
    else {
        while ((u1 < u1n) && (_buffer[u1] == _buffer[u2])) {
            u1++;
            u2++;
        }
    }

    return (u1 < u1n) ? ((u2 < u2n) ? _buffer[u1] - _buffer[u2] : 1) : ((u2 < u2n) ? -1 : 0);
}

int DivSufSort::ssCompare(int p1, int p2, int depth)
{
    int u1 = depth + _sa[p1];
    int u2 = depth + _sa[p2];
    const int u1n = _sa[p1 + 1] + 2;
    const int u2n = _sa[p2 + 1] + 2;

    if (u1n - u1 > u2n - u2) {
        while ((u2 < u2n) && (_buffer[u1] == _buffer[u2])) {
            u1++;
            u2++;
        }
    }
    else {
        while ((u1 < u1n) && (_buffer[u1] == _buffer[u2])) {
            u1++;
            u2++;
        }
    }

    return (u1 < u1n) ? ((u2 < u2n) ? _buffer[u1] - _buffer[u2] : 1) : ((u2 < u2n) ? -1 : 0);
}

void DivSufSort::ssInplaceMerge(int pa, int first, int middle, int last, int depth)
{
    while (true) {
        int p, x;

        if (_sa[last - 1] < 0) {
            x = 1;
            p = pa + ~_sa[last - 1];
        }
        else {
            x = 0;
            p = pa + _sa[last - 1];
        }

        int a = first;
        int r = -1;

        for (int len = middle - first, half = (len >> 1); len > 0; len = half, half >>= 1) {
            const int b = a + half;
            const int q = ssCompare(pa + ((_sa[b] >= 0) ? _sa[b] : ~_sa[b]), p, depth);

            if (q < 0) {
                a = b + 1;
                half -= ((len & 1) ^ 1);
            }
            else
                r = q;
        }

        if (a < middle) {
            if (r == 0)
                _sa[a] = ~_sa[a];

            ssRotate(a, middle, last);
            last -= (middle - a);
            middle = a;

            if (first == middle)
                break;
        }

        last--;

        if (x != 0) {
            last--;

            while (_sa[last] < 0)
                last--;
        }

        if (middle == last)
            break;
    }
}

void DivSufSort::ssRotate(int first, int middle, int last)
{
    int l = middle - first;
    int r = last - middle;

    while ((l > 0) && (r > 0)) {
        if (l == r) {
            ssBlockSwap(first, middle, l);
            break;
        }

        if (l < r) {
            int a = last - 1;
            int b = middle - 1;
            int t = _sa[a];

            while (true) {
                _sa[a--] = _sa[b];
                _sa[b--] = _sa[a];

                if (b < first) {
                    _sa[a] = t;
                    last = a;
                    r -= (l + 1);

                    if (r <= l)
                        break;

                    a--;
                    b = middle - 1;
                    t = _sa[a];
                }
            }
        }
        else {
            int a = first;
            int b = middle;
            int t = _sa[a];

            while (true) {
                _sa[a++] = _sa[b];
                _sa[b++] = _sa[a];

                if (last <= b) {
                    _sa[a] = t;
                    first = a + 1;
                    l -= (r + 1);

                    if (l <= r)
                        break;

                    a++;
                    b = middle;
                    t = _sa[a];
                }
            }
        }
    }
}

void DivSufSort::ssBlockSwap(int a, int b, int n)
{
    while (n > 0) {
        const int t = _sa[a];
        _sa[a] = _sa[b];
        _sa[b] = t;
        n--;
        a++;
        b++;
    }
}

void DivSufSort::ssSwapMerge(int pa, int first, int middle, int last, int buf,
    int bufSize, int depth)
{
    int check = 0;

    while (true) {
        if (last - middle <= bufSize) {
            if ((first < middle) && (middle < last))
                ssMergeBackward(pa, first, middle, last, buf, depth);

            if (((check & 1) != 0)
                || (((check & 2) != 0) && (ssCompare(pa + getIndex(_sa[first - 1]),
                                               pa + _sa[first], depth)
                                              == 0))) {
                _sa[first] = ~_sa[first];
            }

            if (((check & 4) != 0)
                && ((ssCompare(pa + getIndex(_sa[last - 1]), pa + _sa[last], depth) == 0))) {
                _sa[last] = ~_sa[last];
            }

            StackElement* se = _mergeStack->pop();

            if (se == nullptr)
                return;

            first = se->_a;
            middle = se->_b;
            last = se->_c;
            check = se->_d;
            continue;
        }

        if (middle - first <= bufSize) {
            if (first < middle)
                ssMergeForward(pa, first, middle, last, buf, depth);

            if (((check & 1) != 0)
                || (((check & 2) != 0) && (ssCompare(pa + getIndex(_sa[first - 1]),
                                               pa + _sa[first], depth)
                                              == 0))) {
                _sa[first] = ~_sa[first];
            }

            if (((check & 4) != 0)
                && ((ssCompare(pa + getIndex(_sa[last - 1]), pa + _sa[last], depth) == 0))) {
                _sa[last] = ~_sa[last];
            }

            StackElement* se = _mergeStack->pop();

            if (se == nullptr)
                return;

            first = se->_a;
            middle = se->_b;
            last = se->_c;
            check = se->_d;
            continue;
        }

        int len = (middle - first < last - middle) ? middle - first : last - middle;
        int m = 0;

        for (int half = len >> 1; len > 0; len = half, half >>= 1) {
            if (ssCompare(pa + getIndex(_sa[middle + m + half]), pa + getIndex(_sa[middle - m - half - 1]), depth) < 0) {
                m += (half + 1);
                half -= ((len & 1) ^ 1);
            }
        }

        if (m > 0) {
            int lm = middle - m;
            int rm = middle + m;
            ssBlockSwap(lm, middle, m);
            int l = middle;
            int r = l;
            int next = 0;

            if (rm < last) {
                if (_sa[rm] < 0) {
                    _sa[rm] = ~_sa[rm];

                    if (first < lm) {
                        l--;

                        while (_sa[l] < 0)
                            l--;

                        next |= 4;
                    }

                    next |= 1;
                }
                else if (first < lm) {
                    while (_sa[r] < 0)
                        r++;

                    next |= 2;
                }
            }

            if (l - first <= last - r) {
                _mergeStack->push(r, rm, last, (next & 3) | (check & 4), 0);
                middle = lm;
                last = l;
                check = (check & 3) | (next & 4);
            }
            else {
                if ((r == middle) && ((next & 2) != 0))
                    next ^= 6;

                _mergeStack->push(first, lm, l, (check & 3) | (next & 4), 0);
                first = r;
                middle = rm;
                check = (next & 3) | (check & 4);
            }
        }
        else {
            if (ssCompare(pa + getIndex(_sa[middle - 1]), pa + _sa[middle], depth) == 0) {
                _sa[middle] = ~_sa[middle];
            }

            if (((check & 1) != 0)
                || (((check & 2) != 0) && (ssCompare(pa + getIndex(_sa[first - 1]),
                                               pa + _sa[first], depth)
                                              == 0))) {
                _sa[first] = ~_sa[first];
            }

            if (((check & 4) != 0)
                && ((ssCompare(pa + getIndex(_sa[last - 1]), pa + _sa[last], depth) == 0))) {
                _sa[last] = ~_sa[last];
            }

            StackElement* se = _mergeStack->pop();

            if (se == nullptr)
                return;

            first = se->_a;
            middle = se->_b;
            last = se->_c;
            check = se->_d;
        }
    }
}

void DivSufSort::ssMergeForward(int pa, int first, int middle, int last, int buf,
    int depth)
{
    const int bufEnd = buf + middle - first - 1;
    ssBlockSwap(buf, first, middle - first);
    int a = first;
    int b = buf;
    int c = middle;
    const int t = _sa[a];

    while (true) {
        const int r = ssCompare(pa + _sa[b], pa + _sa[c], depth);

        if (r < 0) {
            do {
                _sa[a++] = _sa[b];

                if (bufEnd <= b) {
                    _sa[bufEnd] = t;
                    return;
                }

                _sa[b++] = _sa[a];
            } while (_sa[b] < 0);
        }
        else if (r > 0) {
            do {
                _sa[a++] = _sa[c];
                _sa[c++] = _sa[a];

                if (last <= c) {
                    while (b < bufEnd) {
                        _sa[a++] = _sa[b];
                        _sa[b++] = _sa[a];
                    }

                    _sa[a] = _sa[b];
                    _sa[b] = t;
                    return;
                }
            } while (_sa[c] < 0);
        }
        else {
            _sa[c] = ~_sa[c];

            do {
                _sa[a++] = _sa[b];

                if (bufEnd <= b) {
                    _sa[bufEnd] = t;
                    return;
                }

                _sa[b++] = _sa[a];
            } while (_sa[b] < 0);

            do {
                _sa[a++] = _sa[c];
                _sa[c++] = _sa[a];

                if (last <= c) {
                    while (b < bufEnd) {
                        _sa[a++] = _sa[b];
                        _sa[b++] = _sa[a];
                    }

                    _sa[a] = _sa[b];
                    _sa[b] = t;
                    return;
                }
            } while (_sa[c] < 0);
        }
    }
}

void DivSufSort::ssMergeBackward(int pa, int first, int middle, int last, int buf,
    int depth)
{
    const int bufEnd = buf + last - middle - 1;
    ssBlockSwap(buf, middle, last - middle);
    int x = 0;
    int p1, p2;

    if (_sa[bufEnd] < 0) {
        p1 = pa + ~_sa[bufEnd];
        x |= 1;
    }
    else
        p1 = pa + _sa[bufEnd];

    if (_sa[middle - 1] < 0) {
        p2 = pa + ~_sa[middle - 1];
        x |= 2;
    }
    else
        p2 = pa + _sa[middle - 1];

    int a = last - 1;
    int b = bufEnd;
    int c = middle - 1;
    const int t = _sa[a];

    while (true) {
        const int r = ssCompare(p1, p2, depth);

        if (r > 0) {
            if ((x & 1) != 0) {
                do {
                    _sa[a--] = _sa[b];
                    _sa[b--] = _sa[a];
                } while (_sa[b] < 0);

                x ^= 1;
            }

            _sa[a--] = _sa[b];

            if (b <= buf) {
                _sa[buf] = t;
                break;
            }

            _sa[b--] = _sa[a];

            if (_sa[b] < 0) {
                p1 = pa + ~_sa[b];
                x |= 1;
            }
            else
                p1 = pa + _sa[b];
        }
        else if (r < 0) {
            if ((x & 2) != 0) {
                do {
                    _sa[a--] = _sa[c];
                    _sa[c--] = _sa[a];
                } while (_sa[c] < 0);

                x ^= 2;
            }

            _sa[a--] = _sa[c];
            _sa[c--] = _sa[a];

            if (c < first) {
                while (buf < b) {
                    _sa[a--] = _sa[b];
                    _sa[b--] = _sa[a];
                }

                _sa[a] = _sa[b];
                _sa[b] = t;
                break;
            }

            if (_sa[c] < 0) {
                p2 = pa + ~_sa[c];
                x |= 2;
            }
            else
                p2 = pa + _sa[c];
        }
        else // r = 0
        {
            if ((x & 1) != 0) {
                do {
                    _sa[a--] = _sa[b];
                    _sa[b--] = _sa[a];
                } while (_sa[b] < 0);

                x ^= 1;
            }

            _sa[a--] = ~_sa[b];

            if (b <= buf) {
                _sa[buf] = t;
                break;
            }

            _sa[b--] = _sa[a];

            if ((x & 2) != 0) {
                do {
                    _sa[a--] = _sa[c];
                    _sa[c--] = _sa[a];
                } while (_sa[c] < 0);

                x ^= 2;
            }

            _sa[a--] = _sa[c];
            _sa[c--] = _sa[a];

            if (c < first) {
                while (buf < b) {
                    _sa[a--] = _sa[b];
                    _sa[b--] = _sa[a];
                }

                _sa[a] = _sa[b];
                _sa[b] = t;
                break;
            }

            if (_sa[b] < 0) {
                p1 = pa + ~_sa[b];
                x |= 1;
            }
            else
                p1 = pa + _sa[b];

            if (_sa[c] < 0) {
                p2 = pa + ~_sa[c];
                x |= 2;
            }
            else
                p2 = pa + _sa[c];
        }
    }
}

void DivSufSort::ssInsertionSort(int pa, int first, int last, int depth)
{
    for (int i = last - 2; i >= first; i--) {
        const int t = pa + _sa[i];
        int j = i + 1;
        int r;

        while ((r = ssCompare(t, pa + _sa[j], depth)) > 0) {
            do {
                _sa[j - 1] = _sa[j];
                j++;
            } while ((j < last) && (_sa[j] < 0));

            if (j >= last)
                break;
        }

        if (r == 0)
            _sa[j] = ~_sa[j];

        _sa[j - 1] = t - pa;
    }
}

inline int DivSufSort::ssIsqrt(int x)
{
    if (x >= (SS_BLOCKSIZE * SS_BLOCKSIZE))
        return SS_BLOCKSIZE;

    const int e = ((x & 0xFFFF0000) != 0) ? (((x & 0xFF000000) != 0) ? 24 + LOG_TABLE[(x >> 24) & 0xFF]
                                                                     : 16 + LOG_TABLE[(x >> 16) & 0xFF])
                                          : (((x & 0x0000FF00) != 0) ? 8 + LOG_TABLE[(x >> 8) & 0xFF]
                                                                     : LOG_TABLE[x & 0xFF]);

    if (e < 8)
        return SQQ_TABLE[x] >> 4;

    int y;

    if (e >= 16) {
        y = SQQ_TABLE[x >> ((e - 6) - (e & 1))] << ((e >> 1) - 7);

        if (e >= 24) {
            y = (y + 1 + x / y) >> 1;
        }

        y = (y + 1 + x / y) >> 1;
    }
    else {
        y = (SQQ_TABLE[x >> ((e - 6) - (e & 1))] >> (7 - (e >> 1))) + 1;
    }

    return (x < y * y) ? y - 1 : y;
}

void DivSufSort::ssMultiKeyIntroSort(const int pa, int first, int last, int depth)
{
    int limit = ssIlg(last - first);
    int x = 0;

    while (true) {
        if (last - first <= SS_INSERTIONSORT_THRESHOLD) {
            if (last - first > 1)
                ssInsertionSort(pa, first, last, depth);

            StackElement* se = _ssStack->pop();

            if (se == nullptr)
                return;

            first = se->_a;
            last = se->_b;
            depth = se->_c;
            limit = se->_d;
            continue;
        }

        const int idx = depth;

        if (limit == 0)
            ssHeapSort(idx, pa, first, last - first);

        limit--;
        int a;

        if (limit < 0) {
            int v = _buffer[idx + _sa[pa + _sa[first]]];

            for (a = first + 1; a < last; a++) {
                if ((x = _buffer[idx + _sa[pa + _sa[a]]]) != v) {
                    if (a - first > 1)
                        break;

                    v = x;
                    first = a;
                }
            }

            if (_buffer[idx + _sa[pa + _sa[first]] - 1] < v)
                first = ssPartition(pa, first, a, depth);

            if (a - first <= last - a) {
                if (a - first > 1) {
                    _ssStack->push(a, last, depth, -1, 0);
                    last = a;
                    depth++;
                    limit = ssIlg(a - first);
                }
                else {
                    first = a;
                    limit = -1;
                }
            }
            else {
                if (last - a > 1) {
                    _ssStack->push(first, a, depth + 1, ssIlg(a - first), 0);
                    first = a;
                    limit = -1;
                }
                else {
                    last = a;
                    depth++;
                    limit = ssIlg(a - first);
                }
            }

            continue;
        }

        // choose pivot
        a = ssPivot(idx, pa, first, last);
        const int v = _buffer[idx + _sa[pa + _sa[a]]];
        swapInSA(first, a);
        int b = first;

        // partition
        while (++b < last) {
            if ((x = _buffer[idx + _sa[pa + _sa[b]]]) != v)
                break;
        }

        a = b;

        if ((a < last) && (x < v)) {
            while (++b < last) {
                if ((x = _buffer[idx + _sa[pa + _sa[b]]]) > v)
                    break;

                if (x == v) {
                    swapInSA(b, a);
                    a++;
                }
            }
        }

        int c = last;

        while (--c > b) {
            if ((x = _buffer[idx + _sa[pa + _sa[c]]]) != v)
                break;
        }

        int d = c;

        if ((b < d) && (x > v)) {
            while (--c > b) {
                if ((x = _buffer[idx + _sa[pa + _sa[c]]]) < v)
                    break;

                if (x == v) {
                    swapInSA(c, d);
                    d--;
                }
            }
        }

        while (b < c) {
            swapInSA(b, c);

            while (++b < c) {
                if ((x = _buffer[idx + _sa[pa + _sa[b]]]) > v)
                    break;

                if (x == v) {
                    swapInSA(b, a);
                    a++;
                }
            }

            while (--c > b) {
                if ((x = _buffer[idx + _sa[pa + _sa[c]]]) < v)
                    break;

                if (x == v) {
                    swapInSA(c, d);
                    d--;
                }
            }
        }

        if (a <= d) {
            c = b - 1;
            int s = (a - first > b - a) ? b - a : a - first;

            for (int e = first, f = b - s; s > 0; s--, e++, f++)
                swapInSA(e, f);

            s = (d - c > last - d - 1) ? last - d - 1 : d - c;

            for (int e = b, f = last - s; s > 0; s--, e++, f++)
                swapInSA(e, f);

            a = first + (b - a);
            c = last - (d - c);
            b = (v <= _buffer[idx + _sa[pa + _sa[a]] - 1]) ? a : ssPartition(pa, a, c, depth);

            if (a - first <= last - c) {
                if (last - c <= c - b) {
                    _ssStack->push(b, c, depth + 1, ssIlg(c - b), 0);
                    _ssStack->push(c, last, depth, limit, 0);
                    last = a;
                }
                else if (a - first <= c - b) {
                    _ssStack->push(c, last, depth, limit, 0);
                    _ssStack->push(b, c, depth + 1, ssIlg(c - b), 0);
                    last = a;
                }
                else {
                    _ssStack->push(c, last, depth, limit, 0);
                    _ssStack->push(first, a, depth, limit, 0);
                    first = b;
                    last = c;
                    depth++;
                    limit = ssIlg(c - b);
                }
            }
            else {
                if (a - first <= c - b) {
                    _ssStack->push(b, c, depth + 1, ssIlg(c - b), 0);
                    _ssStack->push(first, a, depth, limit, 0);
                    first = c;
                }
                else if (last - c <= c - b) {
                    _ssStack->push(first, a, depth, limit, 0);
                    _ssStack->push(b, c, depth + 1, ssIlg(c - b), 0);
                    first = c;
                }
                else {
                    _ssStack->push(first, a, depth, limit, 0);
                    _ssStack->push(c, last, depth, limit, 0);
                    first = b;
                    last = c;
                    depth++;
                    limit = ssIlg(c - b);
                }
            }
        }
        else {
            if (_buffer[idx + _sa[pa + _sa[first]] - 1] < v) {
                first = ssPartition(pa, first, last, depth);
                limit = ssIlg(last - first);
            }
            else {
                limit++;
            }

            depth++;
        }
    }
}

int DivSufSort::ssPivot(int td, int pa, int first, int last)
{
    int t = last - first;
    int middle = first + (t >> 1);

    if (t <= 512) {
        return (t <= 32) ? ssMedian3(td, pa, first, middle, last - 1) : ssMedian5(td, pa, first, first + (t >> 2), middle, last - 1 - (t >> 2), last - 1);
    }

    t >>= 3;
    first = ssMedian3(td, pa, first, first + t, first + (t << 1));
    middle = ssMedian3(td, pa, middle - t, middle, middle + t);
    last = ssMedian3(td, pa, last - 1 - (t << 1), last - 1 - t, last - 1);
    return ssMedian3(td, pa, first, middle, last);
}

inline int DivSufSort::ssMedian5(const int idx, int pa, int v1, int v2, int v3, int v4, int v5)
{
    short* buf0 = &_buffer[idx];
    int* buf1 = &_sa[pa];

    if (buf0[buf1[_sa[v2]]] > buf0[buf1[_sa[v3]]]) {
        const int t = v2;
        v2 = v3;
        v3 = t;
    }

    if (buf0[buf1[_sa[v4]]] > buf0[buf1[_sa[v5]]]) {
        const int t = v4;
        v4 = v5;
        v5 = t;
    }

    if (buf0[buf1[_sa[v2]]] > buf0[buf1[_sa[v4]]]) {
        const int t1 = v2;
        v2 = v4;
        v4 = t1;
        const int t2 = v3;
        v3 = v5;
        v5 = t2;
    }

    if (buf0[buf1[_sa[v1]]] > buf0[buf1[_sa[v3]]]) {
        const int t = v1;
        v1 = v3;
        v3 = t;
    }

    if (buf0[buf1[_sa[v1]]] > buf0[buf1[_sa[v4]]]) {
        const int t1 = v1;
        v1 = v4;
        v4 = t1;
        const int t2 = v3;
        v3 = v5;
        v5 = t2;
    }

    return (buf0[buf1[_sa[v3]]] > buf0[buf1[_sa[v4]]]) ? v4 : v3;
}

inline int DivSufSort::ssMedian3(int idx, int pa, int v1, int v2, int v3)
{
    short* buf0 = &_buffer[idx];
    int* buf1 = &_sa[pa];

    if (buf0[buf1[_sa[v1]]] > buf0[buf1[_sa[v2]]]) {
        const int t = v1;
        v1 = v2;
        v2 = t;
    }

    if (buf0[buf1[_sa[v2]]] > buf0[buf1[_sa[v3]]]) {
        return (buf0[buf1[_sa[v1]]] > buf0[buf1[_sa[v3]]]) ? v1 : v3;
    }

    return v2;
}

int DivSufSort::ssPartition(int pa, int first, int last, int depth)
{
    int a = first - 1;
    int b = last;
    const int d = depth - 1;
    const int pb = pa + 1;

    while (true) {
        a++;

        for (; (a < b) && (_sa[pa + _sa[a]] + d >= _sa[pb + _sa[a]]);) {
            _sa[a] = ~_sa[a];
            a++;
        }

        b--;

        for (; (b > a) && (_sa[pa + _sa[b]] + d < _sa[pb + _sa[b]]);)
            b--;

        if (b <= a)
            break;

        const int t = ~_sa[b];
        _sa[b] = _sa[a];
        _sa[a] = t;
    }

    if (first < a)
        _sa[first] = ~_sa[first];

    return a;
}

void DivSufSort::ssHeapSort(int idx, int pa, int saIdx, int size)
{
    int m = size;

    if ((size & 1) == 0) {
        m--;

        if (_buffer[idx + _sa[pa + _sa[saIdx + (m >> 1)]]] < _buffer[idx + _sa[pa + _sa[saIdx + m]]])
            swapInSA(saIdx + m, saIdx + (m >> 1));
    }

    for (int i = (m >> 1) - 1; i >= 0; i--)
        ssFixDown(idx, pa, saIdx, i, m);

    if ((size & 1) == 0) {
        swapInSA(saIdx, saIdx + m);
        ssFixDown(idx, pa, saIdx, 0, m);
    }

    for (int i = m - 1; i > 0; i--) {
        int t = _sa[saIdx];
        _sa[saIdx] = _sa[saIdx + i];
        ssFixDown(idx, pa, saIdx, 0, i);
        _sa[saIdx + i] = t;
    }
}

void DivSufSort::ssFixDown(int idx, int pa, int saIdx, int i, int size)
{
    const int v = _sa[saIdx + i];
    const int c = _buffer[idx + _sa[pa + v]];
    int j = (i << 1) + 1;

    while (j < size) {
        int k = j;
        j++;
        int d = _buffer[idx + _sa[pa + _sa[saIdx + k]]];
        const int e = _buffer[idx + _sa[pa + _sa[saIdx + j]]];

        if (d < e) {
            k = j;
            d = e;
        }

        if (d <= c)
            break;

        _sa[saIdx + i] = _sa[saIdx + k];
        i = k;
        j = (i << 1) + 1;
    }

    _sa[i + saIdx] = v;
}

inline int DivSufSort::ssIlg(int n)
{
    return ((n & 0xFF00) != 0) ? 8 + LOG_TABLE[(n >> 8) & 0xFF]
                               : LOG_TABLE[n & 0xFF];
}

inline void DivSufSort::swapInSA(int a, int b)
{
    const int tmp = _sa[a];
    _sa[a] = _sa[b];
    _sa[b] = tmp;
}

// Tandem Repeat Sort
void DivSufSort::trSort(int n, int depth)
{
    TRBudget budget(trIlg(n) * 2 / 3, n);

    for (int isad = n + depth; _sa[0] > -n; isad += (isad - n)) {
        int first = 0;
        int skip = 0;
        int unsorted = 0;

        do {
            const int t = _sa[first];

            if (t < 0) {
                first -= t;
                skip += t;
            }
            else {
                if (skip != 0) {
                    _sa[first + skip] = skip;
                    skip = 0;
                }

                const int last = _sa[n + t] + 1;

                if (last - first > 1) {
                    budget._count = 0;
                    trIntroSort(n, isad, first, last, budget);

                    if (budget._count != 0)
                        unsorted += budget._count;
                    else
                        skip = first - last;
                }
                else if (last - first == 1)
                    skip = -1;

                first = last;
            }
        } while (first < n);

        if (skip != 0)
            _sa[first + skip] = skip;

        if (unsorted == 0)
            break;
    }
}

uint64 DivSufSort::trPartition(int isad, int first, int middle, int last, int v)
{
    int x = 0;
    int b = middle;

    while (b < last) {
        x = _sa[isad + _sa[b]];

        if (x != v)
            break;

        b++;
    }

    int a = b;

    if ((a < last) && (x < v)) {
        while ((++b < last) && ((x = _sa[isad + _sa[b]]) <= v)) {
            if (x == v) {
                swapInSA(a, b);
                a++;
            }
        }
    }

    int c = last - 1;

    while (c > b) {
        x = _sa[isad + _sa[c]];

        if (x != v)
            break;

        c--;
    }

    int d = c;

    if ((b < d) && (x > v)) {
        while ((--c > b) && ((x = _sa[isad + _sa[c]]) >= v)) {
            if (x == v) {
                swapInSA(c, d);
                d--;
            }
        }
    }

    while (b < c) {
        swapInSA(c, b);

        while ((++b < c) && ((x = _sa[isad + _sa[b]]) <= v)) {
            if (x == v) {
                swapInSA(a, b);
                a++;
            }
        }

        while ((--c > b) && ((x = _sa[isad + _sa[c]]) >= v)) {
            if (x == v) {
                swapInSA(c, d);
                d--;
            }
        }
    }

    if (a <= d) {
        c = b - 1;
        int s = a - first;

        if (s > b - a)
            s = b - a;

        for (int e = first, f = b - s; s > 0; s--, e++, f++)
            swapInSA(e, f);

        s = d - c;

        if (s >= last - d)
            s = last - d - 1;

        for (int e = b, f = last - s; s > 0; s--, e++, f++)
            swapInSA(e, f);

        first += (b - a);
        last -= (d - c);
    }

    return (((uint64)first) << 32) | (((uint64)last) & (uint64)0xFFFFFFFF);
}

void DivSufSort::trIntroSort(int isa, int isad, int first, int last, TRBudget& budget)
{
    const int incr = isad - isa;
    int limit = trIlg(last - first);
    int trlink = -1;

    while (true) {
        if (limit < 0) {
            if (limit == -1) {
                // tandem repeat partition
                uint64 res = trPartition(isad - incr, first, first, last, last - 1);
                const int a = (int)(res >> 32);
                const int b = (int)res;

                // update ranks
                if (a < last) {
                    for (int c = first, v = a - 1; c < a; c++)
                        _sa[isa + _sa[c]] = v;
                }

                if (b < last) {
                    for (int c = a, v = b - 1; c < b; c++)
                        _sa[isa + _sa[c]] = v;
                }

                // push
                if (b - a > 1) {
                    _trStack->push(0, a, b, 0, 0);
                    _trStack->push(isad - incr, first, last, -2, trlink);
                    trlink = _trStack->size() - 2;
                }

                if (a - first <= last - b) {
                    if (a - first > 1) {
                        _trStack->push(isad, b, last, trIlg(last - b), trlink);
                        last = a;
                        limit = trIlg(a - first);
                    }
                    else if (last - b > 1) {
                        first = b;
                        limit = trIlg(last - b);
                    }
                    else {
                        StackElement* se = _trStack->pop();

                        if (se == nullptr)
                            return;

                        isad = se->_a;
                        first = se->_b;
                        last = se->_c;
                        limit = se->_d;
                        trlink = se->_e;
                    }
                }
                else {
                    if (last - b > 1) {
                        _trStack->push(isad, first, a, trIlg(a - first), trlink);
                        first = b;
                        limit = trIlg(last - b);
                    }
                    else if (a - first > 1) {
                        last = a;
                        limit = trIlg(a - first);
                    }
                    else {
                        StackElement* se = _trStack->pop();

                        if (se == nullptr)
                            return;

                        isad = se->_a;
                        first = se->_b;
                        last = se->_c;
                        limit = se->_d;
                        trlink = se->_e;
                    }
                }
            }
            else if (limit == -2) {
                // tandem repeat copy
                StackElement* se = _trStack->pop();

                if (se->_d == 0) {
                    trCopy(isa, first, se->_b, se->_c, last, isad - isa);
                }
                else {
                    if (trlink >= 0)
                        _trStack->get(trlink)->_d = -1;

                    trPartialCopy(isa, first, se->_b, se->_c, last, isad - isa);
                }

                se = _trStack->pop();

                if (se == nullptr)
                    return;

                isad = se->_a;
                first = se->_b;
                last = se->_c;
                limit = se->_d;
                trlink = se->_e;
            }
            else {
                // sorted partition
                if (_sa[first] >= 0) {
                    int a = first;

                    do {
                        _sa[isa + _sa[a]] = a;
                        a++;
                    } while ((a < last) && (_sa[a] >= 0));

                    first = a;
                }

                if (first < last) {
                    int a = first;

                    do {
                        _sa[a] = ~_sa[a];
                        a++;
                    } while (_sa[a] < 0);

                    int next = (_sa[isa + _sa[a]] != _sa[isad + _sa[a]]) ? trIlg(a - first + 1) : -1;
                    a++;

                    if (a < last) {
                        const int v = a - 1;

                        for (int b = first; b < a; b++)
                            _sa[isa + _sa[b]] = v;
                    }

                    // push
                    if (budget.check(a - first) == true) {
                        if (a - first <= last - a) {
                            _trStack->push(isad, a, last, -3, trlink);
                            isad += incr;
                            last = a;
                            limit = next;
                        }
                        else {
                            if (last - a > 1) {
                                _trStack->push(isad + incr, first, a, next, trlink);
                                first = a;
                                limit = -3;
                            }
                            else {
                                isad += incr;
                                last = a;
                                limit = next;
                            }
                        }
                    }
                    else {
                        if (trlink >= 0)
                            _trStack->get(trlink)->_d = -1;

                        if (last - a > 1) {
                            first = a;
                            limit = -3;
                        }
                        else {
                            StackElement* se = _trStack->pop();

                            if (se == nullptr)
                                return;

                            isad = se->_a;
                            first = se->_b;
                            last = se->_c;
                            limit = se->_d;
                            trlink = se->_e;
                        }
                    }
                }
                else {
                    StackElement* se = _trStack->pop();

                    if (se == nullptr)
                        return;

                    isad = se->_a;
                    first = se->_b;
                    last = se->_c;
                    limit = se->_d;
                    trlink = se->_e;
                }
            }

            continue;
        }

        if (last - first <= TR_INSERTIONSORT_THRESHOLD) {
            trInsertionSort(isad, first, last);
            limit = -3;
            continue;
        }

        if (limit == 0) {
            trHeapSort(isad, first, last - first);
            int a = last - 1;

            while (first < a) {
                int b = a - 1;

                for (int x = _sa[isad + _sa[a]]; (first <= b) && (_sa[isad + _sa[b]] == x); b--)
                    _sa[b] = ~_sa[b];

                a = b;
            }

            limit = -3;
            continue;
        }

        limit--;

        // choose pivot
        swapInSA(first, trPivot(_sa, isad, first, last));
        int v = _sa[isad + _sa[first]];

        // partition
        uint64 res = trPartition(isad, first, first + 1, last, v);
        const int a = (int)(res >> 32);
        const int b = (int)(res & 0xFFFFFFFFL);

        if (last - first != b - a) {
            const int next = (_sa[isa + _sa[a]] != v) ? trIlg(b - a) : -1;
            v = a - 1;

            // update ranks
            for (int c = first; c < a; c++)
                _sa[isa + _sa[c]] = v;

            if (b < last) {
                v = b - 1;

                for (int c = a; c < b; c++)
                    _sa[isa + _sa[c]] = v;
            }

            // push
            if ((b - a > 1) && (budget.check(b - a) == true)) {
                if (a - first <= last - b) {
                    if (last - b <= b - a) {
                        if (a - first > 1) {
                            _trStack->push(isad + incr, a, b, next, trlink);
                            _trStack->push(isad, b, last, limit, trlink);
                            last = a;
                        }
                        else if (last - b > 1) {
                            _trStack->push(isad + incr, a, b, next, trlink);
                            first = b;
                        }
                        else {
                            isad += incr;
                            first = a;
                            last = b;
                            limit = next;
                        }
                    }
                    else if (a - first <= b - a) {
                        if (a - first > 1) {
                            _trStack->push(isad, b, last, limit, trlink);
                            _trStack->push(isad + incr, a, b, next, trlink);
                            last = a;
                        }
                        else {
                            _trStack->push(isad, b, last, limit, trlink);
                            isad += incr;
                            first = a;
                            last = b;
                            limit = next;
                        }
                    }
                    else {
                        _trStack->push(isad, b, last, limit, trlink);
                        _trStack->push(isad, first, a, limit, trlink);
                        isad += incr;
                        first = a;
                        last = b;
                        limit = next;
                    }
                }
                else {
                    if (a - first <= b - a) {
                        if (last - b > 1) {
                            _trStack->push(isad + incr, a, b, next, trlink);
                            _trStack->push(isad, first, a, limit, trlink);
                            first = b;
                        }
                        else if (a - first > 1) {
                            _trStack->push(isad + incr, a, b, next, trlink);
                            last = a;
                        }
                        else {
                            isad += incr;
                            first = a;
                            last = b;
                            limit = next;
                        }
                    }
                    else if (last - b <= b - a) {
                        if (last - b > 1) {
                            _trStack->push(isad, first, a, limit, trlink);
                            _trStack->push(isad + incr, a, b, next, trlink);
                            first = b;
                        }
                        else {
                            _trStack->push(isad, first, a, limit, trlink);
                            isad += incr;
                            first = a;
                            last = b;
                            limit = next;
                        }
                    }
                    else {
                        _trStack->push(isad, first, a, limit, trlink);
                        _trStack->push(isad, b, last, limit, trlink);
                        isad += incr;
                        first = a;
                        last = b;
                        limit = next;
                    }
                }
            }
            else {
                if ((b - a > 1) && (trlink >= 0))
                    _trStack->get(trlink)->_d = -1;

                if (a - first <= last - b) {
                    if (a - first > 1) {
                        _trStack->push(isad, b, last, limit, trlink);
                        last = a;
                    }
                    else if (last - b > 1) {
                        first = b;
                    }
                    else {
                        StackElement* se = _trStack->pop();

                        if (se == nullptr)
                            return;

                        isad = se->_a;
                        first = se->_b;
                        last = se->_c;
                        limit = se->_d;
                        trlink = se->_e;
                    }
                }
                else {
                    if (last - b > 1) {
                        _trStack->push(isad, first, a, limit, trlink);
                        first = b;
                    }
                    else if (a - first > 1) {
                        last = a;
                    }
                    else {
                        StackElement* se = _trStack->pop();

                        if (se == nullptr)
                            return;

                        isad = se->_a;
                        first = se->_b;
                        last = se->_c;
                        limit = se->_d;
                        trlink = se->_e;
                    }
                }
            }
        }
        else {
            if (budget.check(last - first) == true) {
                limit = trIlg(last - first);
                isad += incr;
            }
            else {
                if (trlink >= 0)
                    _trStack->get(trlink)->_d = -1;

                StackElement* se = _trStack->pop();

                if (se == nullptr)
                    return;

                isad = se->_a;
                first = se->_b;
                last = se->_c;
                limit = se->_d;
                trlink = se->_e;
            }
        }
    }
}

int DivSufSort::trPivot(int arr[], int isad, int first, int last)
{
    int t = last - first;
    int middle = first + (t >> 1);

    if (t <= 512) {
        if (t <= 32)
            return trMedian3(arr, isad, first, middle, last - 1);

        t >>= 2;
        return trMedian5(arr, isad, first, first + t, middle, last - 1 - t, last - 1);
    }

    t >>= 3;
    first = trMedian3(arr, isad, first, first + t, first + (t << 1));
    middle = trMedian3(arr, isad, middle - t, middle, middle + t);
    last = trMedian3(arr, isad, last - 1 - (t << 1), last - 1 - t, last - 1);
    return trMedian3(arr, isad, first, middle, last);
}

inline int DivSufSort::trMedian5(int _sa[], int isad, int v1, int v2, int v3, int v4, int v5)
{
    if (_sa[isad + _sa[v2]] > _sa[isad + _sa[v3]]) {
        int t = v2;
        v2 = v3;
        v3 = t;
    }

    if (_sa[isad + _sa[v4]] > _sa[isad + _sa[v5]]) {
        const int t = v4;
        v4 = v5;
        v5 = t;
    }

    if (_sa[isad + _sa[v2]] > _sa[isad + _sa[v4]]) {
        const int t1 = v2;
        v2 = v4;
        v4 = t1;
        const int t2 = v3;
        v3 = v5;
        v5 = t2;
    }

    if (_sa[isad + _sa[v1]] > _sa[isad + _sa[v3]]) {
        const int t = v1;
        v1 = v3;
        v3 = t;
    }

    if (_sa[isad + _sa[v1]] > _sa[isad + _sa[v4]]) {
        const int t1 = v1;
        v1 = v4;
        v4 = t1;
        const int t2 = v3;
        v3 = v5;
        v5 = t2;
    }

    if (_sa[isad + _sa[v3]] > _sa[isad + _sa[v4]])
        return v4;

    return v3;
}

inline int DivSufSort::trMedian3(int _sa[], int isad, int v1, int v2, int v3)
{
    if (_sa[isad + _sa[v1]] > _sa[isad + _sa[v2]]) {
        const int t = v1;
        v1 = v2;
        v2 = t;
    }

    if (_sa[isad + _sa[v2]] > _sa[isad + _sa[v3]]) {
        if (_sa[isad + _sa[v1]] > _sa[isad + _sa[v3]])
            return v1;

        return v3;
    }

    return v2;
}

void DivSufSort::trHeapSort(int isad, int saIdx, int size)
{
    int m = size;

    if ((size & 1) == 0) {
        m--;

        if (_sa[isad + _sa[saIdx + (m >> 1)]] < _sa[isad + _sa[saIdx + m]])
            swapInSA(saIdx + m, saIdx + (m >> 1));
    }

    for (int i = (m >> 1) - 1; i >= 0; i--)
        trFixDown(isad, saIdx, i, m);

    if ((size & 1) == 0) {
        swapInSA(saIdx, saIdx + m);
        trFixDown(isad, saIdx, 0, m);
    }

    for (int i = m - 1; i > 0; i--) {
        const int t = _sa[saIdx];
        _sa[saIdx] = _sa[saIdx + i];
        trFixDown(isad, saIdx, 0, i);
        _sa[saIdx + i] = t;
    }
}

void DivSufSort::trFixDown(int isad, int saIdx, int i, int size)
{
    const int v = _sa[saIdx + i];
    const int c = _sa[isad + v];
    int j = (i << 1) + 1;

    while (j < size) {
        int k = j;
        j++;
        int d = _sa[isad + _sa[saIdx + k]];
        const int e = _sa[isad + _sa[saIdx + j]];

        if (d < e) {
            k = j;
            d = e;
        }

        if (d <= c)
            break;

        _sa[saIdx + i] = _sa[saIdx + k];
        i = k;
        j = (i << 1) + 1;
    }

    _sa[saIdx + i] = v;
}

void DivSufSort::trInsertionSort(int isad, int first, int last)
{
    for (int a = first + 1; a < last; a++) {
        int b = a - 1;
        const int t = _sa[a];
        int r = _sa[isad + t] - _sa[isad + _sa[b]];

        while (r < 0) {
            do {
                _sa[b + 1] = _sa[b];
                b--;
            } while ((b >= first) && (_sa[b] < 0));

            if (b < first)
                break;

            r = _sa[isad + t] - _sa[isad + _sa[b]];
        }

        if (r == 0)
            _sa[b] = ~_sa[b];

        _sa[b + 1] = t;
    }
}

void DivSufSort::trPartialCopy(int isa, int first, int a, int b, int last, int depth)
{
    const int v = b - 1;
    int lastRank = -1;
    int newRank = -1;
    int d = a - 1;

    for (int c = first; c <= d; c++) {
        const int s = _sa[c] - depth;

        if ((s >= 0) && (_sa[isa + s] == v)) {
            d++;
            _sa[d] = s;
            const int rank = _sa[isa + s + depth];

            if (lastRank != rank) {
                lastRank = rank;
                newRank = d;
            }

            _sa[isa + s] = newRank;
        }
    }

    lastRank = -1;

    for (int e = d; first <= e; e--) {
        const int rank = _sa[isa + _sa[e]];

        if (lastRank != rank) {
            lastRank = rank;
            newRank = e;
        }

        if (newRank != rank) {
            _sa[isa + _sa[e]] = newRank;
        }
    }

    lastRank = -1;
    const int e = d + 1;
    d = b;

    for (int c = last - 1; d > e; c--) {
        const int s = _sa[c] - depth;

        if ((s >= 0) && (_sa[isa + s] == v)) {
            d--;
            _sa[d] = s;
            const int rank = _sa[isa + s + depth];

            if (lastRank != rank) {
                lastRank = rank;
                newRank = d;
            }

            _sa[isa + s] = newRank;
        }
    }
}

void DivSufSort::trCopy(int isa, int first, int a, int b, int last, int depth)
{
    const int v = b - 1;
    int d = a - 1;

    for (int c = first; c <= d; c++) {
        const int s = _sa[c] - depth;

        if ((s >= 0) && (_sa[isa + s] == v)) {
            d++;
            _sa[d] = s;
            _sa[isa + s] = d;
        }
    }

    const int e = d + 1;
    d = b;

    for (int c = last - 1; d > e; c--) {
        const int s = _sa[c] - depth;

        if ((s >= 0) && (_sa[isa + s] == v)) {
            d--;
            _sa[d] = s;
            _sa[isa + s] = d;
        }
    }
}

int DivSufSort::trIlg(int n)
{
    return ((n & 0xFFFF0000) != 0) ? (((n & 0xFF000000) != 0) ? 24 + LOG_TABLE[(n >> 24) & 0xFF]
                                                              : 16 + LOG_TABLE[(n >> 16) & 0xFF])
                                   : (((n & 0x0000FF00) != 0) ? 8 + LOG_TABLE[(n >> 8) & 0xFF]
                                                              : LOG_TABLE[n & 0xFF]);
}

StackElement::StackElement()
{
    _a = 0;
    _b = 0;
    _c = 0;
    _d = 0;
    _e = 0;
}

Stack::Stack(int size)
{
    _arr = new StackElement*[size];
    _length = size;

    for (int i = 0; i < _length; i++)
        _arr[i] = new StackElement();
}

Stack::~Stack()
{
    for (int i = 0; i < _length; i++)
        delete _arr[i];

    delete[] _arr;
}

void Stack::push(int a, int b, int c, int d, int e)
{
    StackElement* elt = _arr[_index];
    elt->_a = a;
    elt->_b = b;
    elt->_c = c;
    elt->_d = d;
    elt->_e = e;
    _index++;
}

inline StackElement* Stack::pop()
{
    return (_index == 0) ? nullptr : _arr[--_index];
}

TRBudget::TRBudget(int chance, int incval)
{
    _chance = chance;
    _remain = incval;
    _incVal = incval;
}

bool TRBudget::check(int size)
{
    if (size <= _remain) {
        _remain -= size;
        return true;
    }

    if (_chance == 0) {
        _count += size;
        return false;
    }

    _remain += (_incVal - size);
    _chance--;
    return true;
}
