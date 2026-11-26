#include "hw/xbox/hle/shader/vsh.h"
#include "hw/xbox/hle/shader/ff.h"
#include "hw/xbox/hle/vk/horplus_layout.h"
#include "hle_test.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char buf[65536];

/* Instructions from the nv2a_vsh_cpu disassembler tests (comment = meaning). */
static const uint32_t k_prog[][4] = {
    { 0x00000000, 0x0420061B, 0x083613FC, 0x5011F818 }, /* MOV oD0, v3 + RCP R1.w, R1.w */
    { 0x00000000, 0x00EC801B, 0x0836186C, 0x20702800 }, /* DP4 oPos.z, v0, c[100] */
    { 0x00000000, 0x0047401A, 0xC434186C, 0x2070E800 }, /* MUL oPos.xyz, R12.xyz, c[58].xyz */
    { 0x00000000, 0x0400001B, 0x083613FC, 0x2070F82C }, /* RCP oFog, v0.w */
    { 0x00000000, 0x01A00000, 0x0436106C, 0x20700FF8 }, /* ARL A0, R0.x */
    { 0x00000000, 0x006F20BF, 0x9C001456, 0x7C000002 }, /* ADD R0.xy, c[A0+121].zw, -c[A0+121].xy */
    { 0x00000000, 0x0240081B, 0x1436186C, 0x2F20F824 }, /* MUL R2, R1, c[0] + MOV oD1, v4 */
    { 0x00000000, 0x0087601B, 0xC400286C, 0x3070E801 }, /* MAD oPos.xyz, R12, R1.x, c[59] (final) */
};
#define NSLOTS (sizeof(k_prog) / sizeof(k_prog[0]))

static void dump(const char *name)
{
    const char *dir = getenv("HLE_TEST_DUMP");
    if (dir) {
        char path[512];
        snprintf(path, sizeof(path), "%s/%s.vert", dir, name);
        FILE *f = fopen(path, "wb");
        if (f) {
            fputs(buf, f);
            fclose(f);
        }
    }
}

static void test_program(void)
{
    HleVshKey k = { .in_mask = (1u << 0) | (1u << 3) | (1u << 4) };
    size_t n = hle_vsh_glsl(&k_prog[0][0], NSLOTS, &k, buf, sizeof(buf));
    HT_CHECK(n > 0 && n == strlen(buf));
    dump("vsh_program");
    HT_CHECK(strstr(buf, "layout(location = 0) in vec4 v0_in;") != NULL);
    HT_CHECK(strstr(buf, "layout(location = 3) in vec4 v3_in;") != NULL);
    HT_CHECK(strstr(buf, "layout(location = 1) in vec4") == NULL);
    /* registers the arrays do not feed read their current values */
    HT_CHECK(strstr(buf, "vec4 v1 = vcur[1];") != NULL);
    HT_CHECK(strstr(buf, "{ vec4 c[192]; vec4 vcur[16]; }") != NULL);
    /* paired MAC + ILU: both computed before either writes */
    HT_CHECK(strstr(buf, "vec4 m = v3.xyzw;\n    vec4 i = rcp4((R1.wwww).x);\n"
                         "    oD0.xyzw = m.xyzw;\n    R1.w = i.w;") != NULL);
    HT_CHECK(strstr(buf, "oPos.z = m.z;") != NULL);
    HT_CHECK(strstr(buf, "mulz(oPos.xyzz, c[58].xyzz)") != NULL);   /* R12 reads oPos */
    HT_CHECK(strstr(buf, "oFog.x = i.w;") != NULL);     /* the top masked component */
    HT_CHECK(strstr(buf, "vFog = oFog.x;") != NULL);
    HT_CHECK(strstr(buf, "A0 = int(floor(m.x + 1.0 / 1024.0));") != NULL);
    HT_CHECK(strstr(buf, "c[clamp(A0 + 121, 0, 191)].zwww") != NULL);
    HT_CHECK(strstr(buf, "-c[clamp(A0 + 121, 0, 191)].xyyy") != NULL);
    HT_CHECK(strstr(buf, "oD1.xyzw = ") != NULL);
    HT_CHECK(strstr(buf, "(mulz(oPos.xyzw, R1.xxxx) + c[59].xyzw)") != NULL);
    HT_CHECK(strstr(buf, "gl_Position") != NULL);
    HT_CHECK(strstr(buf, "flat out") == NULL);
    k.flat = 1;
    HT_CHECK(hle_vsh_glsl(&k_prog[0][0], NSLOTS, &k, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, "layout(location = 0) flat out vec4 vDiffuse;") != NULL);
}

/* S32K attributes arrive as ivec4, CMP as one packed int */
static void test_attr_kinds(void)
{
    HleVshKey k = { .in_mask = 0x19, .int_mask = 0x08, .cmp_mask = 0x10 };
    HT_CHECK(hle_vsh_glsl(&k_prog[0][0], NSLOTS, &k, buf, sizeof(buf)) > 0);
    dump("vsh_kinds");
    HT_CHECK(strstr(buf, "layout(location = 0) in vec4 v0_in;") != NULL);
    HT_CHECK(strstr(buf, "layout(location = 3) in ivec4 v3_in;") != NULL);
    HT_CHECK(strstr(buf, "layout(location = 4) in uvec2 v4_in;") != NULL);
    HT_CHECK(strstr(buf, "int v4p = int(v4_in.x | (v4_in.y << 16u));") != NULL);
    HT_CHECK(strstr(buf, "vec4 v3 = vec4(v3_in);") != NULL);
    HT_CHECK(strstr(buf, "bitfieldExtract(v4p, 22, 10)") != NULL);
}

/* a program without a FINAL instruction is rejected */
static void test_no_final(void)
{
    HleVshKey k = { .in_mask = 1 };
    HT_CHECK(hle_vsh_glsl(&k_prog[0][0], NSLOTS - 1, &k, buf, sizeof(buf)) == 0);
}

/* stops at the FINAL instruction (trailing garbage ignored) */
static void test_stops_at_final(void)
{
    uint32_t t[NSLOTS + 1][4];
    memcpy(t, k_prog, sizeof(k_prog));
    memset(t[NSLOTS], 0xFF, sizeof(t[NSLOTS]));
    HleVshKey k = { .in_mask = 1 };
    size_t a = hle_vsh_glsl(&t[0][0], NSLOTS + 1, &k, buf, sizeof(buf));
    HT_CHECK(a > 0);
    HT_CHECK(strstr(buf, "slot 8") == NULL);
}

/* a program writing no oFog is not fogged: factor 1 under FOGTABLEMODE
 * NONE, distance 0 under the table modes (Halo's Bink movie quad) */
static void test_unwritten_fog(void)
{
    uint32_t t[NSLOTS][4];
    memcpy(t, k_prog, sizeof(k_prog));
    memset(t[3], 0, sizeof(t[3]));              /* RCP oFog -> NOP */
    HleVshKey k = { .in_mask = 1 };
    HT_CHECK(hle_vsh_glsl(&t[0][0], NSLOTS, &k, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, "vFog = 1.0;") != NULL);
    HT_CHECK(strstr(buf, "oFog.x =") == NULL);
    k.fog_table = 1;
    HT_CHECK(hle_vsh_glsl(&t[0][0], NSLOTS, &k, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, "vFog = 0.0;") != NULL);
}

/* a buffer too small fails instead of truncating */
static void test_overflow(void)
{
    HleVshKey k = { .in_mask = 1 };
    HT_CHECK(hle_vsh_glsl(&k_prog[0][0], NSLOTS, &k, buf, 512) == 0);
}

static void test_horplus_projection(void)
{
    const HleVshKey key = { .in_mask = 1 };
    uint32_t camera[][4] = {
        { 0, 0x00e0601b, 0x08361800, 0x00001800 }, /* DP4 oPos.w, v0, c[3] */
        { 0, 0x06000000, 0x000003ff, 0x10180000 }, /* RCC R1.x, R12.w */
        { 0, 0x0087601b, 0x0800286c, 0x3000c801 }, /* MAD oPos.xy, v0, R1.x, c[59] */
    };
    const uint32_t hud[][4] = {
        { 0, 0x002760ff, 0x0c000000, 0x00001800 }, /* MOV oPos.w, c[59].w (may be non-unit) */
        { 0, 0x0020001b, 0x08000000, 0x0000c801 }, /* MOV oPos.xy, v0 */
    };
    const char *widen = "ndc.x *= pc.fog.w";
    unsigned mask = 0;
    HT_EQ_INT(hle_vsh_perspective_constant(&camera[0][0], 3, &key, &mask), 3);
    HT_EQ_INT(mask, 7);
    HT_EQ_INT(hle_vsh_perspective_constant(&hud[0][0], 2, &key, &mask), -1);
    HT_CHECK(hle_vsh_glsl(&camera[0][0], 3, &key, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, widen) == NULL);
    g_hle_horplus_scale = 0.75f;
    HT_CHECK(hle_vsh_glsl(&camera[0][0], 3, &key, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, widen) != NULL);
    HT_CHECK(strstr(buf, "abs(c[3]), vec4(1.0,1.0,1.0,0.0)") != NULL);
    dump("vsh_horplus_camera");
    HT_CHECK(hle_vsh_glsl(&hud[0][0], 2, &key, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, widen) == NULL);
    HT_EQ_INT(hle_vsh_screen_input(&hud[0][0], 2), 0);
    HT_CHECK(strstr(buf, "ndc.x = ndc.x * pc.fog.w + pc.mvp[0][0]") != NULL);
    dump("vsh_horplus_hud");
    HT_EQ_INT(hle_vsh_screen_input(&camera[0][0], 3), -1);
    HT_EQ_INT(hle_vsh_screen_input(&hud[0][0], 1), -1);

    /* X/Y still use R1.x, but a swizzle change makes it the reciprocal of
     * screen Z, so it cannot be used to prove division by output W. */
    camera[1][2] &= ~(1u << 8);
    HT_EQ_INT(hle_vsh_perspective_constant(&camera[0][0], 3, &key, &mask), -1);
    HT_CHECK(hle_vsh_glsl(&camera[0][0], 3, &key, buf, sizeof(buf)) > 0);
    HT_CHECK(strstr(buf, widen) == NULL);
    g_hle_horplus_scale = 0.0f;
}

static void test_horplus_overlay_layout(void)
{
    const HleHorplusBox left = { 0.05f, 0.70f, 0.27f, 0.94f };
    const HleHorplusBox right = { 0.72f, 0.70f, 0.95f, 0.94f };
    const HleHorplusBox menu_tile = { 0.04f, 0.03f, 0.20f, 0.97f };
    const HleHorplusBox heading = { 0.10f, 0.12f, 0.49f, 0.19f };
    bool panel = false;
    HT_EQ_INT(hle_horplus_anchor(left, false, &panel), HLE_HORPLUS_KEEP);
    HT_CHECK(!panel);
    HT_EQ_INT(hle_horplus_anchor((HleHorplusBox){ 0, 0, 1, 1 }, true, &panel), HLE_HORPLUS_KEEP);
    HT_CHECK(!panel);
    HT_EQ_INT(hle_horplus_anchor(left, true, &panel), HLE_HORPLUS_LEFT);
    HT_EQ_INT(hle_horplus_anchor(right, true, &panel), HLE_HORPLUS_RIGHT);
    HT_EQ_INT(hle_horplus_anchor((HleHorplusBox){ 0.45f, 0.10f, 0.55f, 0.18f }, true, &panel),
              HLE_HORPLUS_CENTER);
    HT_EQ_INT(hle_horplus_anchor((HleHorplusBox){ 0.25f, 0.10f, 0.50f, 0.18f }, true, &panel),
              HLE_HORPLUS_KEEP);
    HT_EQ_INT(hle_horplus_anchor(heading, true, &panel), HLE_HORPLUS_KEEP);
    HT_EQ_INT(hle_horplus_anchor(menu_tile, true, &panel), HLE_HORPLUS_KEEP);
    HT_CHECK(panel);
    HT_EQ_INT(hle_horplus_anchor(left, true, &panel), HLE_HORPLUS_KEEP);
}

int main(void)
{
    HT_RUN(test_program);
    HT_RUN(test_attr_kinds);
    HT_RUN(test_no_final);
    HT_RUN(test_stops_at_final);
    HT_RUN(test_unwritten_fog);
    HT_RUN(test_overflow);
    HT_RUN(test_horplus_projection);
    HT_RUN(test_horplus_overlay_layout);
    HT_DONE();
}
