local mp=require 'mp'
local utils=require 'mp.utils'
local s={mode=0,brightness=0.6,edge=0.5,blur=24,scale=1,align=0,yalign=0,fit='fit',shader='native'}
local profiles={
 native={},sd480={'FSRCNNX_x2_8-0-4-1.glsl','KrigBilateral.glsl'},
 hd720={'FSRCNNX_x2_8-0-4-1.glsl','KrigBilateral.glsl'},
 ravu={'ravu-lite-ar-r3.hook'},fsrcnnx8={'FSRCNNX_x2_8-0-4-1.glsl','KrigBilateral.glsl'},
 fsrcnnx16={'FSRCNNX_x2_16-0-4-1.glsl','KrigBilateral.glsl'},sharp={'SSimSuperRes.glsl'}
}
local function anime(tier,mode)
 local main=tier=='fast' and 'M' or 'VL';local small=tier=='fast' and 'S' or 'M'
 local a={'Clamp_Highlights'};local denoise=mode=='c' or mode=='ca'
 if not denoise then a[#a+1]=((mode=='b' or mode=='bb') and 'Restore_CNN_Soft_' or 'Restore_CNN_')..main end
 a[#a+1]=(denoise and 'Upscale_Denoise_CNN_x2_' or 'Upscale_CNN_x2_')..main
 if mode=='aa' then a[#a+1]='Restore_CNN_'..small end
 a[#a+1]='AutoDownscalePre_x2';a[#a+1]='AutoDownscalePre_x4'
 if mode=='bb' then a[#a+1]='Restore_CNN_Soft_'..small end
 if mode=='ca' then a[#a+1]='Restore_CNN_'..small end
 a[#a+1]='Upscale_CNN_x2_'..small
 for i,v in ipairs(a) do a[i]='Anime4K/Anime4K_'..v..'.glsl' end;return a
end
for _,t in ipairs({'fast','hq'}) do for _,m in ipairs({'a','b','c','aa','bb','ca'}) do profiles['anime-'..t..'-'..m]=anime(t,m) end end
local editing=false
local function apply()
 if editing then return end;editing=true
 local d=mp.get_property_native('video-dec-params') or {};local o=mp.get_property_native('osd-dimensions') or {}
 local aspect=math.max(tonumber(d.aspect) or 16/9,.001)
 local target=math.max(o.w or 16,1)/math.max(o.h or 9,1)
 if (tonumber(d.rotate) or 0)%180~=0 then target=1/target end
 local chain={};for _,p in ipairs(profiles[s.shader] or {}) do chain[#chain+1]=mp.command_native({'expand-path','~~/shaders/'..p}) end
 mp.set_property_native('glsl-shaders',chain)
 mp.set_property('glsl-shader-opts','')
 mp.set_property_bool('deband',s.shader=='sd480')
 mp.set_property_number('video-aspect-override',s.fit=='stretch' and target or -1)
 mp.set_property_number('video-align-x',s.align)
 mp.set_property_number('video-align-y',s.yalign)
 local zoom=s.scale
 if s.fit=='fill' then zoom=zoom*math.max(aspect/target,target/aspect) end
 mp.set_property_number('video-zoom',math.log(zoom)/math.log(2))
 editing=false
end
mp.register_script_message('neon-settings',function(raw)
 local n=utils.parse_json(raw);if type(n)~='table' then return end
 s.mode=math.min(2,math.max(0,tonumber(n.mode) or 0));s.brightness=math.min(1,math.max(0,tonumber(n.brightness) or .6))
 s.edge=math.min(1,math.max(0,tonumber(n.edge) or .5));s.blur=math.min(64,math.max(0,tonumber(n.blur) or 24))
 s.scale=math.min(1.5,math.max(.1,tonumber(n.scale) or 1));s.align=math.min(1,math.max(-1,tonumber(n.align) or 0))
 s.yalign=math.min(1,math.max(-1,tonumber(n.yalign) or 0))
 s.fit=(n.fit=='fill' or n.fit=='stretch') and n.fit or 'fit'
 s.shader=profiles[n.shader] and n.shader or 'native';apply()
end)
mp.observe_property('video-dec-params','native',apply)
mp.observe_property('osd-dimensions','native',apply)
mp.register_event('file-loaded',apply)
