// Neon Player: blurred ambient bars with an unmodified centre and optional feather.
//!PARAM neon_mode
//!TYPE int
1
//!PARAM neon_aspect
//!TYPE float
1.7777778
//!PARAM neon_source
//!TYPE float
1.7777778
//!PARAM neon_brightness
//!TYPE float
0.6
//!PARAM neon_edge
//!TYPE float
0.5
//!PARAM neon_blur
//!TYPE float
24
//!PARAM neon_scale
//!TYPE float
1
//!PARAM neon_align
//!TYPE float
0
//!PARAM neon_yalign
//!TYPE float
0

//!HOOK MAIN
//!BIND HOOKED
//!SAVE NEON_SMALL
//!WIDTH 64
//!HEIGHT 36
vec4 hook(){return HOOKED_tex(HOOKED_pos);}

//!HOOK MAIN
//!BIND NEON_SMALL
//!SAVE NEON_BLUR_H
//!WIDTH 64
//!HEIGHT 36
vec4 hook(){vec4 c=vec4(0.0);float total=0.0;float sigma=max(.25,neon_blur/8.0);for(int i=-10;i<=10;i++){float w=exp(-float(i*i)/(2.*sigma*sigma));c+=NEON_SMALL_tex(NEON_SMALL_pos+vec2(float(i)/64.,0.0))*w;total+=w;}return c/total;}

//!HOOK MAIN
//!BIND NEON_BLUR_H
//!SAVE NEON_BLUR
//!WIDTH 64
//!HEIGHT 36
vec4 hook(){vec4 c=vec4(0.0);float total=0.0;float sigma=max(.25,neon_blur/8.0);for(int i=-10;i<=10;i++){float w=exp(-float(i*i)/(2.*sigma*sigma));c+=NEON_BLUR_H_tex(NEON_BLUR_H_pos+vec2(0.0,float(i)/36.))*w;total+=w;}return c/total;}

//!HOOK MAIN
//!BIND HOOKED
//!BIND NEON_BLUR
//!WIDTH neon_source neon_aspect > HOOKED.w * neon_source neon_aspect > ! HOOKED.w neon_source / neon_aspect * * +
//!HEIGHT neon_source neon_aspect > HOOKED.w neon_aspect / * neon_source neon_aspect > ! HOOKED.w neon_source / * +
vec4 hook(){
 vec2 original=vec2(HOOKED_size.x,HOOKED_size.x/neon_source);
 vec2 canvas=vec2(max(original.x,original.y*neon_aspect),max(original.y,original.x/neon_aspect));
 vec2 image=original*neon_scale;
 vec2 offset=(canvas-image)*vec2((neon_align+1.)*.5,(neon_yalign+1.)*.5);
 vec2 point=HOOKED_pos*canvas;vec2 uv=(point-offset)/image;
 bool inside=all(greaterThanEqual(uv,vec2(0)))&&all(lessThanEqual(uv,vec2(1)));
 float seam=1e8;
 if(offset.x>.5)seam=min(seam,uv.x*image.x);
 if(canvas.x-offset.x-image.x>.5)seam=min(seam,(1.-uv.x)*image.x);
 if(offset.y>.5)seam=min(seam,uv.y*image.y);
 if(canvas.y-offset.y-image.y>.5)seam=min(seam,(1.-uv.y)*image.y);
 float feather=min(image.x,image.y)*.045*neon_edge;
 if(inside&&(feather<.001||seam>=feather))return HOOKED_tex(uv);
 vec2 sample_uv=clamp(uv,vec2(0),vec2(1));
 if(neon_mode==2){float cover=max(canvas.x/original.x,canvas.y/original.y);sample_uv=(point-canvas*.5)/(original*cover)+.5;}
 vec3 background=NEON_BLUR_tex(clamp(sample_uv,vec2(0),vec2(1))).rgb*neon_brightness;
 vec2 outside=max(max(-uv,uv-1.),vec2(0));background*=pow(clamp(1.-max(outside.x,outside.y),0.,1.),.5);
 if(inside)return vec4(mix(background,HOOKED_tex(uv).rgb,smoothstep(0.,max(feather,.001),max(seam,0.))),1.0);
 return vec4(background,1.0);
}
