/*
 * hakuX HLE GPU — NV2A vertex program -> GLSL (pure). See vsh.h.
 *
 * Semantics follow NV_vertex_program1_1 plus the NV2A rules games rely on:
 * - a MAC and an ILU operation in one slot read their operands before
 *   either writes (both results are computed into temporaries first);
 * - MUL/MAD: a zero factor gives zero even against inf/NaN;
 * - RCP(0) = +inf, RCC clamps the magnitude to [2^-64, 2^64];
 * - R12 is oPos;
 * - the program leaves oPos in screen space (the XDK appends the viewport
 *   transform through c[-38]/c[-37]): the epilogue maps x/y back to NDC
 *   with the same mapping as pre-transformed vertices, divides z by the
 *   depth maximum and multiplies by w to undo the perspective divide.
 */
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "hw/xbox/hle/shader/ff.h"
#include "hw/xbox/hle/shader/vsh.h"
#include "subprojects/nv2a_vsh_cpu/src/nv2a_vsh_disassembler.h"

typedef struct Buf {
    char *p;
    size_t cap, len;
    bool overflow;
} Buf;

static void put(Buf *b, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b->p + b->len, b->len < b->cap ? b->cap - b->len : 0, fmt, ap);
    va_end(ap);
    if (n < 0 || b->len + (size_t)n >= b->cap) {
        b->overflow = true;
        b->len = b->cap;
        return;
    }
    b->len += (size_t)n;
}

/* NV2A output register file: 0 oPos, 3 oD0, 4 oD1, 5 oFog, 6 oPts, 7 oB0,
 * 8 oB1, 9..12 oT0..oT3 (1, 2 unused). */
static const char *k_out[16] = {
    "oPos", "oUnused1", "oUnused2", "oD0", "oD1", "oFog", "oPts", "oB0", "oB1",
    "oT0", "oT1", "oT2", "oT3", "oUnused13", "oUnused14", "oUnused15",
};

static const char k_sw[4] = { 'x', 'y', 'z', 'w' };

static void mask_str(Nv2aVshWritemask m, char *s)
{
    unsigned v = (unsigned)m, n = 0;
    if (v & 8) s[n++] = 'x';
    if (v & 4) s[n++] = 'y';
    if (v & 2) s[n++] = 'z';
    if (v & 1) s[n++] = 'w';
    s[n] = 0;
}

static void in_str(Buf *b, const Nv2aVshInput *in)
{
    char reg[48];
    switch (in->type) {
    case NV2ART_TEMPORARY:
        snprintf(reg, sizeof(reg), in->index == 12 ? "oPos" : "R%u", in->index);
        break;
    case NV2ART_INPUT:
        snprintf(reg, sizeof(reg), "v%u", in->index & 15);
        break;
    case NV2ART_CONTEXT:
        if (in->is_relative) {
            snprintf(reg, sizeof(reg), "c[clamp(A0 + %u, 0, 191)]", in->index);
        } else {
            snprintf(reg, sizeof(reg), "c[%u]", in->index % 192);
        }
        break;
    default:
        snprintf(reg, sizeof(reg), "vec4(0.0)");
        break;
    }
    put(b, "%s%s.%c%c%c%c", in->is_negated ? "-" : "", reg, k_sw[in->swizzle[0] & 3],
        k_sw[in->swizzle[1] & 3], k_sw[in->swizzle[2] & 3], k_sw[in->swizzle[3] & 3]);
}

/* Result expression of one operation (vec4), operands already swizzled. */
static void op_expr(Buf *b, const Nv2aVshOperation *o)
{
#define I(n) in_str(b, &o->inputs[n])
    switch (o->opcode) {
    case NV2AOP_MOV: I(0); break;
    case NV2AOP_MUL: put(b, "mulz("); I(0); put(b, ", "); I(1); put(b, ")"); break;
    case NV2AOP_ADD: put(b, "("); I(0); put(b, " + "); I(1); put(b, ")"); break;
    case NV2AOP_MAD:
        put(b, "(mulz("); I(0); put(b, ", "); I(1); put(b, ") + "); I(2); put(b, ")");
        break;
    case NV2AOP_DP3: put(b, "vec4(dot(("); I(0); put(b, ").xyz, ("); I(1); put(b, ").xyz))");
        break;
    case NV2AOP_DP4: put(b, "vec4(dot("); I(0); put(b, ", "); I(1); put(b, "))"); break;
    case NV2AOP_DPH:
        put(b, "vec4(dot(("); I(0); put(b, ").xyz, ("); I(1); put(b, ").xyz) + ("); I(1);
        put(b, ").w)");
        break;
    case NV2AOP_DST:
        put(b, "dst4("); I(0); put(b, ", "); I(1); put(b, ")");
        break;
    case NV2AOP_MIN: put(b, "min("); I(0); put(b, ", "); I(1); put(b, ")"); break;
    case NV2AOP_MAX: put(b, "max("); I(0); put(b, ", "); I(1); put(b, ")"); break;
    case NV2AOP_SLT: put(b, "vec4(lessThan("); I(0); put(b, ", "); I(1); put(b, "))"); break;
    case NV2AOP_SGE:
        put(b, "vec4(greaterThanEqual("); I(0); put(b, ", "); I(1); put(b, "))");
        break;
    case NV2AOP_ARL: I(0); break;
    case NV2AOP_RCP: put(b, "rcp4(("); I(0); put(b, ").x)"); break;
    case NV2AOP_RCC: put(b, "rcc4(("); I(0); put(b, ").x)"); break;
    case NV2AOP_RSQ: put(b, "rsq4(("); I(0); put(b, ").x)"); break;
    case NV2AOP_EXP: put(b, "exp4(("); I(0); put(b, ").x)"); break;
    case NV2AOP_LOG: put(b, "log4(("); I(0); put(b, ").x)"); break;
    case NV2AOP_LIT: put(b, "lit4("); I(0); put(b, ")"); break;
    default:         put(b, "vec4(0.0)"); break;
    }
#undef I
}

/* fogw: set when the program writes oFog */
static void out_write(Buf *b, const Nv2aVshOutput *o, const char *val, bool *fogw)
{
    char m[5];
    mask_str(o->writemask, m);
    switch (o->type) {
    case NV2ART_TEMPORARY:
        if (m[0]) {
            if (o->index == 12) {
                put(b, "    oPos.%s = %s.%s;\n", m, val, m);
            } else {
                put(b, "    R%u.%s = %s.%s;\n", o->index % 12, m, val, m);
            }
        }
        break;
    case NV2ART_OUTPUT:
        if (m[0] && (o->index & 15) == 5) {
            /* oFog: the fog coordinate is x, which takes the most
             * significant masked component (a ".w" write lands in x) */
            *fogw = true;
            put(b, "    oFog.x = %s.%c;\n", val, m[strlen(m) - 1]);
        } else if (m[0]) {
            put(b, "    %s.%s = %s.%s;\n", k_out[o->index & 15], m, val, m);
        }
        break;
    case NV2ART_ADDRESS:
        /* ARL floors, but an index the program computes as a whole number
         * (a bone's, from a byte attribute times 765: i / 255 * 765) can land
         * a rounding error below it here where the NV2A lands on it: floored,
         * the vertex took the bone's previous matrix row, and Azurik's model
         * threw a few of its vertices into the ground. A bias far above fp32's
         * error at these magnitudes, far below an intended fraction's. */
        put(b, "    A0 = int(floor(%s.x + 1.0 / 1024.0));\n", val);
        break;
    default:                            /* CONTEXT writes: state programs only */
        break;
    }
}

static const char k_lib[] =
    "vec4 mulz(vec4 a, vec4 b) {\n"
    "  vec4 r = a * b;\n"
    "  vec4 z = vec4(equal(a, vec4(0.0))) + vec4(equal(b, vec4(0.0)));\n"
    "  return mix(r, vec4(0.0), greaterThan(z, vec4(0.0)));\n"
    "}\n"
    "vec4 dst4(vec4 a, vec4 b) { return vec4(1.0, a.y * b.y, a.z, b.w); }\n"
    "vec4 rcp4(float x) { return vec4(x == 0.0 ? uintBitsToFloat(0x7F800000u) : 1.0 / x); }\n"
    "vec4 rcc4(float x) {\n"
    "  float r = 1.0 / x;\n"
    "  float m = clamp(abs(r), uintBitsToFloat(0x1F800000u), uintBitsToFloat(0x5F800000u));\n"
    "  return vec4((r < 0.0 || (r == 0.0 && floatBitsToUint(r) != 0u)) ? -m : m);\n"
    "}\n"
    "vec4 rsq4(float x) {\n"
    "  float a = abs(x);\n"
    "  return vec4(a == 0.0 ? uintBitsToFloat(0x7F800000u) : inversesqrt(a));\n"
    "}\n"
    "vec4 exp4(float x) {\n"
    "  float f = floor(x);\n"
    "  return vec4(exp2(f), x - f, exp2(x), 1.0);\n"
    "}\n"
    "vec4 log4(float x) {\n"
    "  float a = abs(x);\n"
    "  if (a == 0.0) return vec4(-uintBitsToFloat(0x7F800000u), 1.0,\n"
    "                            -uintBitsToFloat(0x7F800000u), 1.0);\n"
    "  float e = floor(log2(a));\n"
    "  return vec4(e, a / exp2(e), log2(a), 1.0);\n"
    "}\n"
    "vec4 lit4(vec4 s) {\n"
    "  float x = max(s.x, 0.0), y = max(s.y, 0.0);\n"
    "  float w = clamp(s.w, -127.9961, 127.9961);\n"
    "  return vec4(1.0, x, x > 0.0 ? exp2(w * log2(y)) : 0.0, 1.0);\n"
    "}\n";

/* The declarations and main's start: the inputs (v0..v15, current values
 * for registers no array feeds), the outputs, the registers. */
static void head(Buf *bp, const HleVshKey *k)
{
    Buf b = *bp;
    const char *interp = k->flat ? "flat " : "";
    put(&b, "#version 450\n%s", hle_push_glsl());
    put(&b, "layout(set = 1, binding = 0, std140) uniform VshConst { vec4 c[192]; "
            "vec4 vcur[16]; };\n"
            "invariant gl_Position;\n");
    for (unsigned i = 0; i < 16; i++) {
        if (k->in_mask & (1u << i)) {
            put(&b, "layout(location = %u) in %s v%u_in;\n", i,
                (k->cmp_mask >> i) & 1 ? "uvec2" : (k->int_mask >> i) & 1 ? "ivec4" : "vec4", i);
        }
    }
    put(&b, "layout(location = 0) %sout vec4 vDiffuse;\n"
            "layout(location = 1) %sout vec4 vSpecular;\n"
            "layout(location = 2) out vec4 vTex0;\n"
            "layout(location = 3) out vec4 vTex1;\n"
            "layout(location = 4) out vec4 vTex2;\n"
            "layout(location = 5) out vec4 vTex3;\n"
            "layout(location = 6) out float vFog;\n"
            "layout(location = 7) %sout vec4 vBack0;\n"
            "layout(location = 8) %sout vec4 vBack1;\n", interp, interp, interp, interp);
    if (k->wbuf) {      /* W-buffering: perspective-correct w for the depth */
        put(&b, "layout(location = 9) out float vW;\n");
    }
    put(&b, "%s", k_lib);
    put(&b, "void main() {\n");
    for (unsigned i = 0; i < 16; i++) {
        if (k->in_mask & (1u << i) && (k->cmp_mask >> i) & 1) {
            /* fetched as two 16-bit halves: the dword is often only 2-byte aligned */
            put(&b, "  int v%up = int(v%u_in.x | (v%u_in.y << 16u));\n"
                    "  vec4 v%u = vec4(vec3(bitfieldExtract(v%up, 0, 11), "
                    "bitfieldExtract(v%up, 11, 11), bitfieldExtract(v%up, 22, 10)) / "
                    "vec3(1023.0, 1023.0, 511.0), 1.0);\n", i, i, i, i, i, i, i);
        } else if (k->in_mask & (1u << i)) {
            put(&b, "  vec4 v%u = vec4(v%u_in);\n", i, i);
        } else {
            /* not in the arrays: the NV2A reads the register's current value
             * (SET_VERTEX_DATA*: Halo's decals take their tint and fade from
             * SetVertexDataColor(9)) */
            put(&b, "  vec4 v%u = vcur[%u];\n", i, i);
        }
    }
    put(&b, "  int A0 = 0;\n"
            "  vec4 R0 = vec4(0.0), R1 = vec4(0.0), R2 = vec4(0.0), R3 = vec4(0.0);\n"
            "  vec4 R4 = vec4(0.0), R5 = vec4(0.0), R6 = vec4(0.0), R7 = vec4(0.0);\n"
            "  vec4 R8 = vec4(0.0), R9 = vec4(0.0), R10 = vec4(0.0), R11 = vec4(0.0);\n"
            "  vec4 oPos = vec4(0.0, 0.0, 0.0, 1.0), oD0 = vec4(0.0, 0.0, 0.0, 1.0);\n"
            "  vec4 oD1 = vec4(0.0, 0.0, 0.0, 1.0), oFog = vec4(0.0, 0.0, 0.0, 1.0);\n"
            "  vec4 oPts = vec4(0.0, 0.0, 0.0, 1.0), oB0 = vec4(0.0, 0.0, 0.0, 1.0);\n"
            "  vec4 oB1 = vec4(0.0, 0.0, 0.0, 1.0), oT0 = vec4(0.0, 0.0, 0.0, 1.0);\n"
            "  vec4 oT1 = vec4(0.0, 0.0, 0.0, 1.0), oT2 = vec4(0.0, 0.0, 0.0, 1.0);\n"
            "  vec4 oT3 = vec4(0.0, 0.0, 0.0, 1.0);\n"
            "  vec4 oUnused1, oUnused2, oUnused13, oUnused14, oUnused15;\n");
    *bp = b;
}

/* Match the XDK projection idiom: X and Y are divided by the same
 * vertex-dependent dot product that the program leaves in oPos.w.
 * We only need provenance, not the actual register values. Stop carrying
 * evidence through operations we cannot recognize. */
typedef enum ProjectionKind {
    PROJ_UNIFORM, PROJ_UNKNOWN, PROJ_VERTEX, PROJ_DOT, PROJ_RECIPROCAL, PROJ_DIVIDED,
} ProjectionKind;

typedef struct ProjectionValue {
    ProjectionKind kind;
    int origin;                 /* identity of the value, or of the divisor */
    bool geometry;              /* depends on an attribute's xyz lanes */
    unsigned constant, mask;    /* position-dependent coefficients of a dot */
} ProjectionValue;

typedef struct ProjectionTrace {
    ProjectionValue reg[13][4]; /* R0..R11 and oPos (the R12 alias) */
    unsigned in_mask, cmp_mask;
    int next_origin;
} ProjectionTrace;

/* The X/Y components of a pass-through program come straight from the same
 * input binding. Follow MOV aliases and invalidate on any other write. */
int hle_vsh_screen_input(const uint32_t *tokens, unsigned nslots)
{
    int source[13][2] = { 0 };
    for (unsigned s = 0; s < nslots && s < HLE_VSH_MAX_SLOTS; s++) {
        Nv2aVshStep step = { 0 };
        if (nv2a_vsh_parse_step(&step, tokens + 4 * s) != NV2AVPR_SUCCESS) {
            return -1;
        }
        const Nv2aVshOperation *ops[] = { &step.mac, &step.ilu };
        int next[2][2] = { 0 };
        for (unsigned i = 0; i < 2; i++) {
            const Nv2aVshInput *in = &ops[i]->inputs[0];
            if (ops[i]->opcode != NV2AOP_MOV || in->is_negated) {
                continue;
            }
            for (unsigned j = 0; j < 2; j++) {
                const unsigned lane = in->swizzle[j];
                if (in->type == NV2ART_INPUT && in->index < 16) {
                    next[i][j] = 1 + (int)(in->index * 4 + lane);
                } else if (in->type == NV2ART_TEMPORARY && in->index <= 12 && lane < 2) {
                    next[i][j] = source[in->index][lane];
                }
            }
        }
        for (unsigned i = 0; i < 2; i++) {
            if (ops[i]->opcode == NV2AOP_NOP) {
                continue;
            }
            for (unsigned j = 0; j < 2; j++) {
                const Nv2aVshOutput *out = &ops[i]->outputs[j];
                const unsigned reg = out->type == NV2ART_TEMPORARY ? out->index :
                    out->type == NV2ART_OUTPUT && out->index == 0 ? 12 : 13;
                if (reg <= 12) {
                    for (unsigned lane = 0; lane < 2; lane++) {
                        if (out->writemask & (8u >> lane)) {
                            source[reg][lane] = next[i][lane];
                        }
                    }
                }
            }
        }
        if (step.is_final) {
            int x = source[12][0] - 1;
            return x >= 0 && x % 4 == 0 && source[12][1] == x + 2 ? x / 4 : -1;
        }
    }
    return -1;
}

static bool varying(ProjectionValue value)
{
    return value.kind == PROJ_VERTEX || value.kind == PROJ_DOT;
}

static ProjectionValue projection_input(const ProjectionTrace *trace, const Nv2aVshInput *input, unsigned lane)
{
    if (input->type == NV2ART_NONE) {
        return (ProjectionValue){ .kind = PROJ_UNIFORM };
    }
    const unsigned swizzle = input->swizzle[lane] & 3;
    ProjectionValue value = { .kind = PROJ_UNKNOWN };
    if (input->type == NV2ART_TEMPORARY && input->index <= 12) {
        value = trace->reg[input->index][swizzle];
    } else if (input->type == NV2ART_INPUT && input->index < 16) {
        const unsigned bit = 1u << input->index;
        if (!(trace->in_mask & bit) || (swizzle == 3 && (trace->cmp_mask & bit))) {
            value = (ProjectionValue){ .kind = PROJ_UNIFORM };
        } else {
            value = (ProjectionValue){ .kind = PROJ_VERTEX, .origin = 1 + 4 * (int)input->index + (int)swizzle,
                                       .geometry = swizzle < 3 };
        }
    } else if (input->type == NV2ART_CONTEXT && !input->is_relative) {
        value = (ProjectionValue){ .kind = PROJ_UNIFORM };
    }
    if (input->is_negated && (varying(value) || value.kind == PROJ_RECIPROCAL)) {
        value.origin = -value.origin;
    }
    return value;
}

static ProjectionValue projection_product(ProjectionValue a, ProjectionValue b)
{
    if (b.kind == PROJ_RECIPROCAL || a.kind == PROJ_UNIFORM) {
        ProjectionValue tmp = a;
        a = b;
        b = tmp;
    }
    if (a.kind == PROJ_RECIPROCAL && varying(b) && a.origin != b.origin && a.origin != -b.origin) {
        return (ProjectionValue){ .kind = PROJ_DIVIDED, .origin = a.origin };
    }
    if (b.kind == PROJ_UNIFORM) {
        return a;
    }
    if (varying(a) && varying(b)) {
        return (ProjectionValue){ .kind = PROJ_VERTEX, .geometry = a.geometry || b.geometry };
    }
    return (ProjectionValue){ .kind = PROJ_UNKNOWN };
}

static ProjectionValue projection_sum(ProjectionValue a, ProjectionValue b)
{
    if (b.kind == PROJ_UNIFORM) {
        return a;
    }
    if (a.kind == PROJ_UNIFORM) {
        return b;
    }
    /* A projected position can carry an additional vertex-dependent screen
     * offset (for instance a displacement along a transformed normal). */
    if (a.kind == PROJ_DIVIDED && varying(b)) {
        return a;
    }
    if (b.kind == PROJ_DIVIDED && varying(a)) {
        return b;
    }
    if (varying(a) && varying(b)) {
        return (ProjectionValue){ .kind = PROJ_VERTEX, .geometry = a.geometry || b.geometry };
    }
    return (ProjectionValue){ .kind = PROJ_UNKNOWN };
}

static ProjectionValue projection_dot(ProjectionTrace *trace, const Nv2aVshOperation *op)
{
    const unsigned lanes = op->opcode == NV2AOP_DP4 ? 4 : 3;
    const Nv2aVshInput *constant = NULL;
    unsigned data = 0;
    for (unsigned input = 0; input < 2; input++) {
        const Nv2aVshInput *candidate = &op->inputs[input];
        if (candidate->type == NV2ART_CONTEXT && !candidate->is_relative) {
            constant = candidate;
            data = 1 - input;
            break;
        }
    }
    bool vertex = false, geometry = false;
    unsigned mask = 0;
    for (unsigned lane = 0; lane < lanes; lane++) {
        for (unsigned input = 0; input < 2; input++) {
            ProjectionValue value = projection_input(trace, &op->inputs[input], lane);
            if (!varying(value) && value.kind != PROJ_UNIFORM) {
                return (ProjectionValue){ .kind = PROJ_UNKNOWN };
            }
            vertex |= varying(value);
            geometry |= value.geometry;
            if (constant && input == data && value.geometry) {
                mask |= 1u << (constant->swizzle[lane] & 3);
            }
        }
    }
    if (op->opcode == NV2AOP_DPH) {
        ProjectionValue offset = projection_input(trace, &op->inputs[1], 3);
        if (!varying(offset) && offset.kind != PROJ_UNIFORM) {
            return (ProjectionValue){ .kind = PROJ_UNKNOWN };
        }
        vertex |= varying(offset);
        geometry |= offset.geometry;
        if (constant == &op->inputs[0] && offset.kind != PROJ_UNIFORM) {
            mask = 0; /* the implicit offset is not supplied by the same constant */
        }
    }
    return (ProjectionValue){ .kind = mask ? PROJ_DOT : vertex ? PROJ_VERTEX : PROJ_UNIFORM,
                              .origin = ++trace->next_origin, .geometry = geometry,
                              .constant = constant ? constant->index % 192 : 0, .mask = mask };
}

static ProjectionValue projection_result(ProjectionTrace *trace, const Nv2aVshOperation *op, unsigned lane)
{
    ProjectionValue a = projection_input(trace, &op->inputs[0], lane);
    ProjectionValue b = projection_input(trace, &op->inputs[1], lane);
    ProjectionValue result = { .kind = PROJ_UNKNOWN };
    switch (op->opcode) {
    case NV2AOP_MOV:
        return a;
    case NV2AOP_DP3:
    case NV2AOP_DP4:
    case NV2AOP_DPH:
        return projection_dot(trace, op);
    case NV2AOP_RCP:
    case NV2AOP_RCC:
        a = projection_input(trace, &op->inputs[0], 0);
        if (a.kind == PROJ_DOT) {
            return (ProjectionValue){ .kind = PROJ_RECIPROCAL, .origin = a.origin };
        }
        return a.kind == PROJ_UNIFORM ? a : result;
    case NV2AOP_ADD:
        result = projection_sum(a, b);
        break;
    case NV2AOP_MUL:
    case NV2AOP_MAD:
        result = projection_product(a, b);
        /* Scaling a reciprocal by a uniform preserves its divisor. Adding
         * an offset to the reciprocal itself no longer does. */
        if (op->opcode == NV2AOP_MAD && result.kind == PROJ_RECIPROCAL) {
            return (ProjectionValue){ .kind = PROJ_UNKNOWN };
        }
        if (op->opcode == NV2AOP_MAD) {
            result = projection_sum(result, projection_input(trace, &op->inputs[2], lane));
        }
        break;
    default:
        break;
    }
    if (varying(result)) {
        result.kind = PROJ_VERTEX;
        result.origin = ++trace->next_origin;
    } else if (result.kind == PROJ_RECIPROCAL && op->opcode == NV2AOP_ADD) {
        result = (ProjectionValue){ .kind = PROJ_UNKNOWN };
    }
    return result;
}

static void projection_step(ProjectionTrace *trace, const Nv2aVshStep *step)
{
    const Nv2aVshOperation *ops[] = { &step->mac, &step->ilu };
    ProjectionValue result[2][4];
    /* MAC and ILU read the registers as they were before this instruction. */
    for (unsigned i = 0; i < 2; i++) {
        if (ops[i]->opcode != NV2AOP_NOP) {
            const Nv2aVshOpcode opcode = ops[i]->opcode;
            const bool scalar = opcode == NV2AOP_DP3 || opcode == NV2AOP_DP4 || opcode == NV2AOP_DPH ||
                opcode == NV2AOP_RCP || opcode == NV2AOP_RCC;
            for (unsigned lane = 0; lane < 4; lane++) {
                result[i][lane] = scalar && lane ? result[i][0] : projection_result(trace, ops[i], lane);
            }
        }
    }
    for (unsigned i = 0; i < 2; i++) {
        if (ops[i]->opcode == NV2AOP_NOP) {
            continue;
        }
        for (unsigned j = 0; j < 2; j++) {
            const Nv2aVshOutput *out = &ops[i]->outputs[j];
            const unsigned reg = out->type == NV2ART_TEMPORARY ? out->index :
                out->type == NV2ART_OUTPUT && out->index == 0 ? 12 : 13;
            if (reg <= 12) {
                for (unsigned lane = 0; lane < 4; lane++) {
                    if (out->writemask & (8u >> lane)) {
                        trace->reg[reg][lane] = result[i][lane];
                    }
                }
            }
        }
    }
}

static int perspective_divisor(const ProjectionTrace *trace, unsigned *mask)
{
    const ProjectionValue *pos = trace->reg[12];
    if (pos[3].kind != PROJ_DOT || pos[0].kind != PROJ_DIVIDED || pos[1].kind != PROJ_DIVIDED ||
        pos[0].origin != pos[3].origin || pos[1].origin != pos[3].origin) {
        return -1;
    }
    *mask = pos[3].mask;
    return (int)pos[3].constant;
}

int hle_vsh_perspective_constant(const uint32_t *tokens, unsigned nslots, const HleVshKey *key,
                                 unsigned *mask)
{
    ProjectionTrace trace = { .in_mask = key->in_mask, .cmp_mask = key->cmp_mask, .next_origin = 64 };
    for (unsigned s = 0; s < nslots && s < HLE_VSH_MAX_SLOTS; s++) {
        Nv2aVshStep step = { 0 };
        if (nv2a_vsh_parse_step(&step, tokens + 4 * s) != NV2AVPR_SUCCESS) {
            break;
        }
        projection_step(&trace, &step);
        if (step.is_final) {
            return perspective_divisor(&trace, mask);
        }
    }
    return -1;
}

static const char *perspective_position(const ProjectionTrace *trace, char expression[96])
{
    unsigned mask;
    const int constant = perspective_divisor(trace, &mask);
    if (constant < 0) {
        return NULL;
    }
    /* The same vertex program can draw UI with an affine matrix. Check the
     * coefficients once per invocation, independently of the vertex's W. */
    snprintf(expression, 96, "dot(abs(c[%d]), vec4(%u.0,%u.0,%u.0,%u.0)) > 1e-6", constant,
             mask & 1u, (mask >> 1) & 1u, (mask >> 2) & 1u, (mask >> 3) & 1u);
    return expression;
}

static void tail(Buf *b, const HleVshKey *k, bool fogw, const char *perspective);

size_t hle_vsh_glsl(const uint32_t *tokens, unsigned nslots, const HleVshKey *k,
                    char *buf, size_t cap)
{
    Buf b = { buf, cap, 0, false };
    head(&b, k);
    bool final = false, fogw = false;
    const bool check_projection = g_hle_horplus_scale && !k->zfree;
    ProjectionTrace projection = { .in_mask = k->in_mask, .cmp_mask = k->cmp_mask, .next_origin = 64 };
    for (unsigned s = 0; s < nslots && s < HLE_VSH_MAX_SLOTS && !final; s++) {
        Nv2aVshStep st;
        memset(&st, 0, sizeof(st));
        if (nv2a_vsh_parse_step(&st, tokens + 4 * s) != NV2AVPR_SUCCESS) {
            return 0;
        }
        final = st.is_final;
        bool mac = st.mac.opcode != NV2AOP_NOP, ilu = st.ilu.opcode != NV2AOP_NOP;
        if (!mac && !ilu) {
            continue;
        }
        if (check_projection) {
            projection_step(&projection, &st);
        }
        put(&b, "  { /* slot %u */\n", s);
        if (mac) {
            put(&b, "    vec4 m = ");
            op_expr(&b, &st.mac);
            put(&b, ";\n");
        }
        if (ilu) {
            put(&b, "    vec4 i = ");
            op_expr(&b, &st.ilu);
            put(&b, ";\n");
        }
        for (unsigned o = 0; mac && o < 2; o++) {
            out_write(&b, &st.mac.outputs[o], "m", &fogw);
        }
        for (unsigned o = 0; ilu && o < 2; o++) {
            out_write(&b, &st.ilu.outputs[o], "i", &fogw);
        }
        put(&b, "  }\n");
    }
    if (!final) {
        return 0;
    }
    char perspective[96];
    tail(&b, k, fogw, check_projection ? perspective_position(&projection, perspective) : NULL);
    return b.overflow ? 0 : b.len;
}

size_t hle_vsh_glsl_wrap(const HleVshKey *k, const char *body, bool fogw, const char *perspective,
                         char *buf, size_t cap)
{
    Buf b = { buf, cap, 0, false };
    head(&b, k);
    put(&b, "%s", body);
    tail(&b, k, fogw, perspective);
    return b.overflow ? 0 : b.len;
}

/* main's end: the outputs from the registers, oPos (screen space) to clip
 * space. fogw: oFog was written. */
static void tail(Buf *bp, const HleVshKey *k, bool fogw, const char *perspective)
{
    Buf b = *bp;
    /* specular off (NV2A SPECULAR_ENABLE clear): the pixel stage sees
     * (0, 0, 0, 1) whatever the program wrote */
    const char *d1 = k->spec_off ? "vec4(0.0, 0.0, 0.0, 1.0)" : "clamp(oD1, 0.0, 1.0)";
    const char *b1 = k->spec_off ? "vec4(0.0, 0.0, 0.0, 1.0)" : "clamp(oB1, 0.0, 1.0)";
    put(&b, "  vDiffuse = clamp(oD0, 0.0, 1.0);\n"
            "  vSpecular = %s;\n"
            "  vBack0 = clamp(oB0, 0.0, 1.0);\n"
            "  vBack1 = %s;\n"
            "  vTex0 = oT0; vTex1 = oT1; vTex2 = oT2; vTex3 = oT3;\n", d1, b1);
    /* A program that leaves oFog alone is not fogged: Halo draws its Bink
     * movies with fog enabled through a program writing no oFog, and they
     * show on the console. Unfogged is factor 1 under FOGTABLEMODE NONE
     * (vertex fog), distance 0 under the table modes. */
    if (fogw) {
        put(&b, "  vFog = oFog.x;\n");
    } else {
        put(&b, "  vFog = %s;\n", k->fog_table ? "0.0" : "1.0");
    }
    put(&b, "  gl_PointSize = max(oPts.x, 1.0) * (0.5 * float(max((pc.flags >> 16) & 255u, 2u)));\n"
            /* screen space -> clip space (the NV2A rasterizer's subpixel
             * placement, ff.h) */
            "  float w = oPos.w;\n"
            "  if (w == 0.0) w = 1.0;\n"
            /* the NV2A keeps w finite (magnitude at most 2^64): D3D's XYZRHW
             * pass-through takes RCP of rhw, and rhw 0 (Otogi's title art)
             * would make w +inf and drop the primitive */
            "  if (isinf(w)) w = w > 0.0 ? uintBitsToFloat(0x5F800000u) : uintBitsToFloat(0xDF800000u);\n"
            "  vec2 scr = oPos.xy;\n"
            HLE_SUBPIXEL_GLSL("scr")
            HLE_TEXEL_EDGE_GLSL("scr")
            "  vec2 ndc = scr * pc.vp + vec2(-1.0, 1.0);\n");
    /* The NV2A clips on the depth it tests: under W-buffering that is w (the
     * pixel stage's), and without a depth test nothing. The program's z then
     * must not clip the primitive (Forza's sky dome past the far plane, its
     * minimap at z far below 0), so the clip z only keeps w >= 0. */
    if (!k->wbuf && !k->zfree) {
        put(&b, HLE_ZNORM_GLSL("oPos.z", "zN"));
    }
    if (g_hle_horplus_scale && !k->zfree && perspective) {
        put(&b, "  if ((pc.flags & %uu) != 0u && (%s)) ndc.x *= pc.fog.w;\n", HLE_HORPLUS_FLAG, perspective);
    }
    if (g_hle_horplus_scale) {
        put(&b, HLE_HUD_ANCHOR_GLSL);
    }
    put(&b, "  gl_Position = vec4(ndc * w, %s, w);\n", k->wbuf || k->zfree ? "0.0" : "zN * w");
    if (k->zclamp && g_hle_glsl_zclamp) {
        put(&b, HLE_ZCLAMP_GLSL);
    }
    if (g_hle_glsl_zw) {
        put(&b, HLE_ZW_NUDGE_GLSL);
    }
    put(&b, "%s}\n", k->wbuf ? "  vW = w;\n" : "");
    *bp = b;
}
