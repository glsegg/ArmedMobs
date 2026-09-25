const fs=require('fs');
for (const f of ['scav.animation.json','scav.geo.json']) {
  const p='D:/deepseek/ArmedMobs/src/main/resources/assets/tarkovscav/'+(f.includes('geo')?'geo/':'animations/')+f;
  const j=JSON.parse(fs.readFileSync(p,'utf8'));
  let bad=[];
  const walk=(o,path)=>{
    if(o===null||o===undefined) return;
    if(typeof o==='number'){ if(!isFinite(o)||Math.abs(o)>1000) bad.push(path+' = '+o); return; }
    if(Array.isArray(o)){ o.forEach((v,i)=>walk(v,path+'['+i+']')); return; }
    if(typeof o==='object'){ for(const k of Object.keys(o)) walk(o[k],path+'.'+k); }
  };
  walk(j,'');
  console.log('=== '+f+' 越界数值(|v|>1000 或非有限) 共 '+bad.length+' 处 ===');
  bad.slice(0,40).forEach(b=>console.log('  '+b));
  if(f.includes('animation')){
    console.log('  动画总数: '+Object.keys(j.animations||{}).length);
    console.log('  动画名: '+Object.keys(j.animations||{}).join(', '));
    console.log('  格式版本: '+JSON.stringify(j.format_version)+'  name: '+j.name);
  }
}
