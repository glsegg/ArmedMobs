const fs=require('fs');
const p='D:/deepseek/ArmedMobs/src/main/resources/assets/tarkovscav/animations/scav.animation.json';
const j=JSON.parse(fs.readFileSync(p,'utf8'));
const lim={scale:4, rotation:400, position:64};
const hits=[];
for(const [anim,a] of Object.entries(j.animations)){
  const bones=a.bones||{};
  for(const [bone,ch] of Object.entries(bones)){
    for(const kind of ['scale','rotation','position']){
      const kf=ch[kind];
      if(!kf) continue;
      const arr = Array.isArray(kf)?kf:(kf.keyframes||kf);
      if(!Array.isArray(arr)) continue;
      for(const k of arr){
        const v=k.pre||k.post||k;
        if(v===undefined) continue;
        const nums=Array.isArray(v)?v:[v];
        for(const n of nums){
          if(typeof n!=='number') continue;
          if(!isFinite(n)||Math.abs(n)>lim[kind]) hits.push({anim,bone,kind,time:(k.time!==undefined?k.time:'-'),val:n});
        }
      }
    }
  }
}
console.log('可疑关键帧共 '+hits.length+' 处（scale>4 / rotation>400 / position>64）');
const by=new Map();
for(const h of hits){ const k=h.anim+' | '+h.bone+' | '+h.kind; by.set(k,(by.get(k)||0)+1); }
[...by.entries()].sort((a,b)=>b[1]-a[1]).slice(0,30).forEach(([k,c])=>console.log('  '+c+'x  '+k));
console.log('--- 前 25 条明细 ---');
hits.slice(0,25).forEach(h=>console.log('  '+h.anim+'  '+h.bone+'  '+h.kind+'  t='+h.time+'  v='+h.val));
// 顺便统计所有 clip 里出现的最大值分布
let maxv={scale:0,rotation:0,position:0};
for(const a of Object.values(j.animations)) for(const ch of Object.values(a.bones||{})) for(const kind of ['scale','rotation','position']) if(ch[kind]){
  const arr=Array.isArray(ch[kind])?ch[kind]:(ch[kind].keyframes||[]);
  for(const k of arr){ const v=k.pre||k.post||k; const nums=Array.isArray(v)?v:[v];
    for(const n of nums) if(typeof n==='number'&&isFinite(n)) maxv[kind]=Math.max(maxv[kind],Math.abs(n)); } }
console.log('--- 全体最大绝对值 --- scale='+maxv.scale+'  rotation='+maxv.rotation+'  position='+maxv.position);
