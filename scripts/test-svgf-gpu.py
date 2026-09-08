"""Actual GLSL temporal/prefilter/atrous regression with synthetic receiver geometry.
Run: uv run --python 3.12 --with moderngl --with numpy python scripts/test-svgf-gpu.py
Requires OpenGL 4.3. Geometry/exposure are fixture adapters, not Minecraft coverage.
"""
from pathlib import Path
import json
import argparse
import re
import moderngl
import numpy as np

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fault',choices=['luma-only','chroma-self-lock'],help='Inject a shader fault to demonstrate that the image regressions turn red')
args=parser.parse_args()
ROOT = Path(__file__).resolve().parents[1] / 'modules/shaders'
W, H = 65, 33
ctx = moderngl.create_standalone_context(require=430)
VERT = '''#version 430
void main() { vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2-1,0,1); }
'''
GEOMETRY = '''
uniform int fixture_surface_mode;
struct FragData { uvec4 data1; ivec2 pixel; };
void frag_data_load(out FragData f, ivec2 p) {
    f.data1 = uvec4(0u, 0x80008000u, 0x80008000u, 0u); f.pixel = p;
    if (fixture_surface_mode == 2 && p.x >= 32) f.data1.yz = uvec2(0x8000ffffu);
}
void frag_data_load_previous(out FragData f, ivec2 p) { frag_data_load(f,p); }
bool frag_data_is_in_world(FragData f) { return true; }
bool frag_data_is_hand(FragData f) { return false; }
bool frag_data_is_light_transmissive(FragData f) { return false; }
vec3 frag_data_geo_normal(FragData f) {
    return fixture_surface_mode == 2 && f.pixel.x >= 32 ? vec3(1,0,0) : vec3(0,0,1);
}
vec3 frag_data_player_pos(FragData f) {
    return vec3(vec2(f.pixel)*0.01, fixture_surface_mode == 1 && f.pixel.x >= 32 ? 0.0625 : 0.0);
}
'''
FRAG = GEOMETRY + '''
FragData _frag_data;
ivec2 frag_tex_coord;
bool frag_is_in_world = true;
bool frag_is_hand = false;
bool frag_is_light_transmissive = false;
bool frag_is_bad_angle = false;
vec3 frag_player_pos;
vec3 frag_geo_normal;
uniform int frameCounter;
uniform float fixture_exposure;
uniform float fixture_previous_exposure;
uniform vec2 fixture_previous_pixel_offset;
const float viewWidth = 65.0;
const float viewHeight = 33.0;
#define PH_VIEW_SIZE vec2(viewWidth,viewHeight)
#define PH_RENDER_SCALE 1.0
void setup_frag_data(int unused_index) {
    frag_tex_coord = ivec2(gl_FragCoord.xy);
    frag_data_load(_frag_data, frag_tex_coord);
    frag_player_pos = frag_data_player_pos(_frag_data);
    frag_geo_normal = frag_data_geo_normal(_frag_data);
}
float load_depth() { return 0.5; }
float get_exposure() { return fixture_exposure; }
float get_previous_exposure() { return fixture_previous_exposure; }
float ph_linearize_depth(float depth) { return depth; }
vec2 ph_previous_frame_jitter() { return vec2(0); }
vec3 ph_reproject_player_pos(vec3 pos, bool hand, vec2 jitter) {
    return vec3((pos.xy / 0.01 + 0.5 + fixture_previous_pixel_offset) / PH_VIEW_SIZE,0.5);
}
'''
def expand(path, passes=5):
    source = (ROOT / path.lstrip('/')).read_text(encoding='utf-8')
    def include(match):
        name = match[1]
        if name.endswith('/frag/world_interface.glsl') or name.endswith('/frag/frame_jitter.glsl'): return ''
        if name == '/photonics/rendering/frag/common.glsl': return FRAG
        if name.endswith('/frag/frag_data.glsl'): return GEOMETRY
        if name == '/photonics/rendering/restir/common.glsl':
            return f'#define PH_RESTIR_ACCUMULATION_FRAMES 32\n#define PH_RESTIR_DENOISER_PASSES {passes}\n#define PH_ENABLE_BLOCKLIGHT\n' + expand('/photonics/utility/color.glsl', passes)
        return expand(name, passes)
    return re.sub(r'#include "([^"]+)"', include, source).replace('//ph_required: uniform', 'uniform')

def program(name, passes=5):
    source=expand('/photonics/rendering/restir/svgf/passes/'+name,passes)
    if args.fault == 'luma-only':
        source=source.replace('return exp(-max(luma_distance, max(chroma_distance.x, chroma_distance.y)));','return exp(-luma_distance);')
    if args.fault == 'chroma-self-lock':
        source=source.replace('6.0f * sqrt(max(chroma_variance, vec2(0.0000000001f)))','vec2(0.00006f)')
    return ctx.program(vertex_shader=VERT, fragment_shader=source)

accum = program('sv0_accumulation.fsh')
prefilter = program('sv1_variance_prefilter.fsh')
atrous = program('sv2_atrous.fsh')
# Zero-pass program must link without the optional attachments/samplers.
raw_accum = program('sv0_accumulation.fsh',0)
undo = program('sv3_undo_exposure.fsh')

def texture(channels, dtype, data=None):
    return ctx.texture((W,H),channels,None if data is None else np.ascontiguousarray(data).tobytes(),dtype=dtype)
def draw(prog, outputs, inputs, **uniforms):
    for unit,(name,tex) in enumerate(inputs.items()):
        if name in prog:
            tex.use(unit); prog[name]=unit
    for name,value in uniforms.items():
        if name in prog: prog[name]=value
    fb=ctx.framebuffer(outputs); fb.use(); ctx.viewport=(0,0,W,H)
    vao=ctx.vertex_array(prog,[]); vao.render(vertices=3); vao.release(); fb.release()
def read_float(tex, channels):
    return np.frombuffer(tex.read(),np.float32).reshape(H,W,channels).copy()
def decode_color(tex):
    packed=np.frombuffer(tex.read(),np.uint32).reshape(H,W,4)
    return np.stack([packed[:,:,0]&65535,packed[:,:,0]>>16,packed[:,:,1]&65535],axis=2).astype(np.uint16).view(np.float16).astype(np.float32)
def decode_age(tex):
    packed=np.frombuffer(tex.read(),np.uint32).reshape(H,W,4)
    return (packed[:,:,1]>>16).astype(np.uint16).view(np.float16).astype(np.float32)
def chroma_moments(rgb):
    co=(rgb[:,:,0]-rgb[:,:,2])*0.5
    cg=rgb[:,:,1]*0.5-(rgb[:,:,0]+rgb[:,:,2])*0.25
    c=np.stack([co,cg],axis=2)
    return np.concatenate([c,c*c],axis=2)

class Temporal:
    def __init__(self):
        self.history = [texture(4,'u4',np.zeros((H,W,4),np.uint32)),texture(4,'f4',np.zeros((H,W,4),np.float32)),texture(1,'f4',np.zeros((H,W),np.float32)),texture(4,'f4',np.zeros((H,W,4),np.float32))]
        self.frame=0
        self.exposure=1.0
        self.expected=np.zeros((H,W,4),np.float32)
        self.age=0
    def add(self, rgb, exposure=1.0, valid=True, check_reference=True, previous_pixel_offset=(0.0,0.0)):
        outputs=[texture(4,'u4'),texture(4,'f4'),texture(1,'f4'),texture(4,'f4')]
        lighting=texture(4,'f4',np.concatenate([rgb,np.ones((H,W,1),np.float32)],axis=2).astype(np.float32))
        inputs=dict(zip(['prev_diffuse_history','prev_fast_diffuse_history','prev_visibility_history','prev_chroma_history'],self.history))
        # Only textureSize is used on current diffuse_history in this pass.
        inputs.update(diffuse_history=self.history[0],di_output=lighting)
        draw(accum,outputs,inputs,frameCounter=self.frame,fixture_exposure=exposure,fixture_previous_exposure=self.exposure,ph_reservoir_splatting_history_valid=int(valid),fixture_surface_mode=0,fixture_previous_pixel_offset=previous_pixel_offset)
        if not valid or self.frame==0: self.expected[:]=0; self.age=0
        ratio=exposure/self.exposure
        self.expected[:,:,:2]*=ratio
        self.expected[:,:,2:]*=ratio*ratio
        self.age=min(self.age,32)+1
        self.expected += (chroma_moments(rgb)-self.expected)/self.age
        error=float(np.max(np.abs(read_float(outputs[3],4)-self.expected)))
        if check_reference:
            assert error < 2e-5, f'Raw chroma moments disagree with CPU recurrence: {error}'
        for tex in self.history: tex.release()
        lighting.release()
        self.history=outputs; self.frame+=1; self.exposure=exposure
        return error
    def spatial(self, surface_mode=0):
        outputs=[texture(4,'u4'),texture(2,'f4')]
        draw(prefilter,outputs,{'diffuse_history':self.history[0],'visibility_history':self.history[2],'chroma_history':self.history[3]},fixture_surface_mode=surface_mode)
        pre=decode_color(outputs[0])
        for iteration in range(5):
            next_outputs=[texture(4,'u4'),texture(2,'f4')]
            draw(atrous,next_outputs,{'prev_denoise_result':outputs[0],'prev_denoise_chroma_variance':outputs[1],'visibility_history':self.history[2]},atrous_iteration=iteration,fixture_surface_mode=surface_mode)
            for tex in outputs: tex.release()
            outputs=next_outputs
        result=decode_color(outputs[0])
        variance=read_float(outputs[1],2)
        assert np.all(np.isfinite(result)) and np.all(np.isfinite(variance))
        assert np.all(variance>=0)
        for tex in outputs: tex.release()
        return result,pre
    def release(self):
        for tex in self.history: tex.release()

background=np.array([0.05,0.2,0.05],np.float32)
stripe=np.array([0.55,0.2-0.5*0.2126/0.7152,0.05],np.float32)
stable=np.broadcast_to(background,(H,W,3)).copy(); stable[:,31:34]=stripe
history=Temporal()
for _ in range(48): history.add(stable)
result,_=history.spatial()
retention=float(np.dot(result[H//2,W//2]-background,stripe-background)/np.dot(stripe-background,stripe-background))
assert retention>0.95, f'Converged narrow colored projection lost {(1-retention)*100:.1f}% contrast'
history.release()

# True fluctuating chroma noise on flat radiance, not a guessed low-variance
# snapshot. GPU temporal moments must discover variance in the luma nullspace.
rng=np.random.default_rng(714)
history=Temporal(); direction=np.array([1,-0.1404/0.5708,-1+2*0.1404/0.5708],np.float32)
for _ in range(64):
    rgb=0.3+rng.choice([-0.1,0.1],size=(H,W,1)).astype(np.float32)*direction
    history.add(rgb)
before=decode_color(history.history[0])
result,_=history.spatial()
interior=np.s_[8:-8,16:-16]
noise_ratio=float(np.mean((result[interior]-0.3)**2)/np.mean((before[interior]-0.3)**2))
assert noise_ratio<0.25, f'Isoluminant temporal noise was locked as detail: MSE ratio {noise_ratio}'
# Exposure is in input radiance; first and second moments scale differently.
history.add(rgb*2,exposure=2)
# Conservative invalidation must clear the new moments in the same frame.
history.add(stable,exposure=1,valid=False)
assert np.max(np.abs(read_float(history.history[3],4)-chroma_moments(stable)))<2e-6
assert np.all(decode_age(history.history[0])==1)
history.release()

# A decisive colored lighting step must reset chroma moments with the same
# confidence as luma history. Recover reset strength from the packed output
# age; allow only its half-float quantization error in this reference check.
history=Temporal()
old=np.broadcast_to(np.array([0.2,0.1,0.1],np.float32),(H,W,3)).copy()
new=np.broadcast_to(np.array([2.0,0.4,0.4],np.float32),(H,W,3)).copy()
for _ in range(40): history.add(old)
history.add(new,check_reference=False)
age=decode_age(history.history[0])
reset=(33.0-age)/32.0
expected=history.expected+(chroma_moments(new)-history.expected)*reset[:,:,None]
reset_error=float(np.max(np.abs(read_float(history.history[3],4)-expected)))
assert float(reset[H//2,W//2])>0.5, 'Fixture did not trigger an anti-lag reset'
assert reset_error<0.0006, f'Chroma moments did not follow anti-lag confidence: {reset_error}'
history.release()

# A rejected reprojected tap must reject its new chroma record too. Poison
# the old responsive sample on one receiver; keep neighboring moments finite.
history=Temporal()
for _ in range(8): history.add(old)
fast=read_float(history.history[1],4); fast[H//2,W//2]=np.nan
history.history[1].write(fast.tobytes())
history.add(new,check_reference=False)
assert abs(float(decode_age(history.history[0])[H//2,W//2])-1.0)<0.001
assert np.max(np.abs(read_float(history.history[3],4)[H//2,W//2]-chroma_moments(new)[H//2,W//2]))<2e-6
history.release()

# Fractional reprojection must normalize chroma moments over exactly the
# accepted bilinear taps. Nonuniform finite moments on rejected taps detect
# accidentally accumulating chroma independently of the shared validity test.
fractional_reprojection_errors={}
previous_offset=(0.25,0.375)
tap_offsets=[(0,0),(1,0),(0,1),(1,1)]
fx,fy=previous_offset
bilinear_weights=np.array([(1-fx)*(1-fy),fx*(1-fy),(1-fx)*fy,fx*fy],np.float64)
tap_means=np.array([[0.06,-0.03],[0.14,0.02],[-0.08,0.11],[0.21,-0.07]],np.float32)
tap_variances=np.array([[0.006,0.004],[0.007,0.005],[0.008,0.006],[0.009,0.007]],np.float32)
tap_moments=np.concatenate([tap_means,tap_means*tap_means+tap_variances],axis=1)
for invalid_taps in [(1,),(0,3)]:
    history=Temporal()
    for _ in range(8): history.add(old)
    moments=read_float(history.history[3],4)
    fast=read_float(history.history[1],4)
    for index,(dx,dy) in enumerate(tap_offsets):
        moments[H//2+dy,W//2+dx]=tap_moments[index]
        if index in invalid_taps: fast[H//2+dy,W//2+dx]=np.nan
    history.history[3].write(moments.tobytes())
    history.history[1].write(fast.tobytes())
    history.add(old,check_reference=False,previous_pixel_offset=previous_offset)
    accepted=np.array([index not in invalid_taps for index in range(4)])
    accepted_weights=bilinear_weights*accepted
    reprojected=np.sum(tap_moments*accepted_weights[:,None],axis=0)/np.sum(accepted_weights)
    # All valid input histories have age eight, so the new observation uses
    # alpha=1/9 after shared accepted-weight normalization.
    expected=reprojected+(chroma_moments(old)[H//2,W//2]-reprojected)/9.0
    actual=read_float(history.history[3],4)[H//2,W//2]
    error=float(np.max(np.abs(actual-expected)))
    fractional_reprojection_errors[str(invalid_taps)]=error
    assert abs(float(decode_age(history.history[0])[H//2,W//2])-9.0)<0.001
    assert error<2e-5, f'Partial bilinear chroma reprojection disagrees with normalized CPU weights: {invalid_taps}, {error}'
    history.release()

# Fresh-history prefilter must not let an outlier's zero chroma variance
# reject all compatible taps. The mature stripe above guards against blurring
# stable lighting when temporal evidence is available.
fresh=np.full((H,W,3),0.2,np.float32); fresh[H//2,W//2]=10.0
history=Temporal(); history.add(fresh); result,pre=history.spatial()
fresh_retention=float((result[H//2,W//2,0]-0.2)/9.8)
assert fresh_retention<0.25, f'Fresh bright outlier self-locked: retention {fresh_retention}'
history.release()

# Geometry disagreement must still stop cross-surface taps, even at maximal
# chroma uncertainty. Test a 1/16-block parallel offset and a different normal.
geometry={}
for surface_mode in [1,2]:
    split=np.full((H,W,3),0.2,np.float32); split[:,32:]=0.8
    history=Temporal(); history.add(split)
    result,_=history.spatial(surface_mode)
    geometry[str(surface_mode)]=float(result[H//2,31,0]-0.2)
    assert geometry[str(surface_mode)]<0.08, f'Geometry guide leaked: {geometry}'
    history.release()
print(json.dumps({'renderer':ctx.info['GL_RENDERER'],'projected_chroma_retention':retention,'isoluminant_noise_mse_ratio':noise_ratio,'fresh_bright_outlier_retention':fresh_retention,'surface_leakage':geometry,'temporal_moments_cpu_reference':'pass','exposure_and_invalidation':'pass','zero_pass_link':'pass','coherent_reset_error':reset_error,'rejected_reprojection_tap':'pass','fractional_reprojection_errors':fractional_reprojection_errors},indent=2))
