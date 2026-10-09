#include <gtest/gtest.h>
#include <thread>
#include "../dsp/ring_buffer.h"

using studioone::dsp::RingBuffer;

TEST(RingBufferTest, WritesAndReadsInOrder) {
    RingBuffer<int> ring(16);
    int data[4] = {1, 2, 3, 4};
    EXPECT_EQ(ring.write(data, 4), 4u);
    EXPECT_EQ(ring.available(), 4u);

    int out[4] = {};
    EXPECT_EQ(ring.read(out, 4), 4u);
    EXPECT_EQ(out[0], 1);
    EXPECT_EQ(out[3], 4);
    EXPECT_EQ(ring.available(), 0u);
}

TEST(RingBufferTest, DropsWritesWhenFull) {
    RingBuffer<int> ring(4);  // capacity 4 => 3 usable slots
    int data[4] = {1, 2, 3, 4};
    EXPECT_EQ(ring.write(data, 4), 3u);
}

TEST(RingBufferTest, SpscConcurrencyStaysConsistent) {
    RingBuffer<int> ring(1024);
    constexpr int kTotal = 100'000;
    std::thread producer([&] {
        for (int i = 0; i < kTotal; ++i) {
            int v = i;
            while (ring.write(&v, 1) != 1) {
                // spin: consumer will drain
            }
        }
    });
    int expected = 0;
    while (expected < kTotal) {
        int v;
        if (ring.read(&v, 1) == 1) {
            EXPECT_EQ(v, expected);
            ++expected;
        }
    }
    producer.join();
}
