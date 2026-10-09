#include <gtest/gtest.h>
#include "../dsp/envelope.h"

using studioone::dsp::AdsrEnvelope;

TEST(EnvelopeTest, AttackRisesAndReleaseFalls) {
    AdsrEnvelope env;
    env.prepare(48000.0);
    env.setAttackMs(2.0);
    env.setDecayMs(50.0);
    env.setSustain(0.7);
    env.setReleaseMs(30.0);

    env.noteOn();
    double level = 0.0;
    for (int i = 0; i < 480; ++i) level = env.next();  // ~10ms
    EXPECT_GT(level, 0.5);

    for (int i = 0; i < 4800; ++i) level = env.next();  // settle to sustain
    EXPECT_NEAR(level, 0.7, 0.05);

    env.noteOff();
    for (int i = 0; i < 4800; ++i) level = env.next();
    EXPECT_LT(level, 0.01);
    EXPECT_TRUE(env.isIdle());
}
