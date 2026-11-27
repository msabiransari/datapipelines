// Combo's layer contract through the production model, frame, marks and interaction layer (1069 A.2-A.5).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { engine, chartFixtures, inputOf, MODULE } from './engine.mjs';
import { plant } from './forms.mjs';

const FILE = 'types/combo.js';
const E = engine(s => plant(s, FILE, 'marks: marks, axes:', 'marks: function (m, hand, h) { R.seen = {m: m, hand: hand, h: h}; return marks(m, hand, h); }, axes:'));
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === 'combo').map(f => [f.name, f.fixture]));
const I = createRequire(import.meta.url)(join(MODULE, '../web/src/main/resources/static/js/datapipelines-chart-interactions.js'))._internal;
const WIDTH = E.measure.measurer(null).fn;
const plain = x => JSON.parse(JSON.stringify(x));
const same = (a,b) => assert.deepEqual(plain(a), plain(b));
const near = (a,b) => assert.ok(Math.abs(a-b) < .011, `${a} != ${b}`);
function draw(fx = FX['bar-line'], w = 640, h = 360, extra = {}, R = E) {
  R.seen = null;
  const r = R.render(inputOf(fx,w,h,extra));
  return {r, ...R.seen};
}
function variant(name, change) { const f=structuredClone(FX[name]); change(f, f.document.config.encoding); return f; }
const marks = r => r.primitives.filter(p => p.role === 'mark' && p.k !== 'group');
const dots = r => marks(r).filter(p => p.k === 'circle');
function interaction(r) { const m=I.modelOf(E,r,WIDTH); m.theme=I.measures(E,'light').theme; return m; }
function box(p) {
  if(p.k === 'rect') return {x:p.x,y:p.y,w:p.w,h:p.h};
  const xs=[],ys=[];
  for(let j=0;j<p.d.length;) { const op=p.d[j], n=E.prim.ARITY[op]; if(n) {xs.push(p.d[j+n-1]); ys.push(p.d[j+n]);} j+=n+1; }
  return {x:Math.min(...xs),y:Math.min(...ys),w:Math.max(...xs)-Math.min(...xs),h:Math.max(...ys)-Math.min(...ys)};
}

test('A.2: layer ids, authored titles, palette and legend keys; one warning per code', () => {
  const {m}=draw(FX['four-layers']);
  same(m.series.map(s=>s.id), ['layer:0','layer:1','layer:2','layer:3']);
  same(m.series.map(s=>s.legendKey), ['legendKeyBar','legendKeyArea','legendKeyLine','legendKeyPoint']);
  same(m.series.map(s=>s.colour), E.theme.SERIES.slice(0,4));
  assert.equal(draw().m.series[0].title,'Revenue');
  const f=variant('two-lines-two-axes',(_,e)=>{e.layers.forEach(l=>{delete l.axis; l.y.scale={type:'log'};});});
  f.columns.rev[0]=-1; f.columns.cost[0]=-2;
  same(draw(f).m.warnings.map(w=>w.code),['chart.rule.log-nonpositive']);
  assert.equal(draw(FX.empty).r.state,'no-data');
  assert.equal(draw(FX['all-null']).r.state,'no-data');
});

test('A.2: all layers retain the same sorted categories over 200 seeded null datasets', () => {
  let seed=1069;
  const rand=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0; return seed/2**32;};
  let checked=0;
  for(let n=0;n<200;n++) {
    const f=variant('four-layers',()=>{});
    for(const key of ['rev','cost','rate','n']) f.columns[key]=f.columns[key].map(x=>rand()<.45?null:x);
    const d=draw(f); if(!d.m) continue;
    for(const s of d.m.subs) same(s.m.xs.map(x=>x.raw), d.m.subs[0].m.xs.map(x=>x.raw));
    checked++;
  }
  assert.equal(checked,200);
});

test('A.2: hidden axes retain their range, empty layers do not add zero, and inherited log applies before counting',()=>{
  const d=draw(FX['two-lines-two-axes']);
  same(d.m.layout.extent({}), d.m.layout.extent({'layer:0':true}));
  const f=variant('two-lines-two-axes',(f,e)=>{delete e.layers[1].axis; f.columns.rev=[50,100,150,200,250,200]; f.columns.cost.fill(null);});
  same(draw(f).m.layout.extent({'layer:0':true,'layer:1':true}),[50,250]);
  for(const right of [false,true]) {
    const f=variant('two-lines-two-axes',(f,e)=>{
      f.columns={m:['A','B'],rev:[1,10],cost:[-1,2]};
      e.layers=[{mark:'line',y:{field:'rev',type:'quantitative',aggregate:'sum',scale:{type:'log'}}},{mark:'point',y:{field:'cost',type:'quantitative',aggregate:'sum'}}];
      if(right) {e.layers.forEach(l=>l.axis='right'); e.layers.push({mark:'line',y:{field:'rev',type:'quantitative',aggregate:'sum'}});}
    });
    assert.equal(draw(f).r.summary.marks,right?5:3);
    assert.equal(draw(f,640,360,{hidden:['layer:0']}).r.summary.marks,right?3:1);
  }
});

test('A.2: layer, row and per-layer mark caps refuse only above their boundary; lines thin',()=>{
  const tooMany=variant('four-layers',(_,e)=>e.layers.push(structuredClone(e.layers[0])));
  assert.equal(draw(tooMany).r.summary.refusal.code,'chart.too_many_series');
  for(const mark of ['bar','point','line','area']) for(const n of [1000,1001]) {
    const f=variant('bar-line',(f,e)=>{
      e.layers[0].mark=mark;
      f.columns={m:Array.from({length:n},(_,j)=>'c'+j),rev:Array(n).fill(2),rate:Array(n).fill(3)};
    });
    const r=draw(f).r;
    assert.equal(r.state,(mark==='bar'||mark==='point')&&n>1000?'refused':'rendered',`${mark} ${n}`);
  }
  for(const n of [10000,10001]) {
    const f=variant('two-lines-two-axes',f=>{f.columns={m:Array.from({length:n},(_,j)=>'c'+j),rev:Array(n).fill(2),cost:Array(n).fill(3)};});
    assert.equal(draw(f).r.state,n===10000?'rendered':'refused');
  }
});

test('A.3: draw order by kind, index order by layer, each line and point cell dotted, only area end dotted',()=>{
  const f=variant('four-layers',(_,e)=>e.layers.reverse());
  const {r}=draw(f);
  same([...new Set(marks(r).map(p=>p.series))],['layer:3','layer:2','layer:1','layer:0']);
  same(r.index.series.map(s=>s.id),['layer:0','layer:1','layer:2','layer:3']);
  same(['layer:0','layer:1','layer:2','layer:3'].map(id=>dots(r).filter(p=>p.series===id).length),[6,6,1,0]);
  assert.equal(r.summary.marks,24);
  assert.ok(marks(r).every(p=>p.datum===null));
  assert.equal(Object.hasOwn(r.index,'totals'),false);
});

test('A.3: L10 extreme dots retain all marks and keep their ring inside 80 by 24; bar plus area asks reach too',()=>{
  const theme={base:'light',tokens:{'--chart-point':16,'--chart-gap':8}};
  for(const name of ['four-layers','bar-line']) {
    const f=variant(name,(_,e)=>{if(name==='bar-line')e.layers[1].mark='area';});
    for(const [w,h,radius,ring] of [[80,24,4.8,2.4],[640,360,16,8]]) {
      const d=draw(f,w,h,{theme});
      assert.equal(d.r.state,'rendered');
      assert.equal(dots(d.r).length,name==='four-layers'?13:1);
      for(const p of dots(d.r)) {near(p.r,radius); near(p.strokeWidth,ring); assert.ok(p.cx-p.r-ring/2>=-.011 && p.cx+p.r+ring/2<=w+.011); assert.ok(p.cy-p.r-ring/2>=-.011 && p.cy+p.r+ring/2<=h+.011);}
    }
  }
});

test('A.3: spans equal every drawn bar box; category centres agree across layers at six frames',()=>{
  let spans=0;
  for(const fx of Object.values(FX)) for(const [w,h] of [[960,540],[480,320],[320,240],[240,140],[160,160],[80,24]]) {
    const {r,hand,m}=draw(fx,w,h); if(r.state!=='rendered')continue;
    for(const s of r.index.series) {
      const sub=m.subs[Number(s.id.slice(6))];
      if(sub.mark!=='bar') {assert.equal(s.spans,undefined); continue;}
      if(s.at.length===0) continue;
      const drawn=marks(r).filter(p=>p.series===s.id), expected=drawn.map(box);
      assert.equal(s.spans.length,expected.length);
      s.spans.forEach((sp,k)=>{const b=expected[k]; near(sp.x,b.x);near(sp.w,b.w);near(Math.min(sp.foot,sp.end),b.y);near(Math.abs(sp.foot-sp.end),b.h);spans++;});
    }
    for(const s of r.index.series) s.at.forEach((at,k)=>near(at,hand.band.start+hand.band.step*(s.datum[k]+.5)));
  }
  assert.ok(spans>=2994);console.log('A.3 spans checked',spans);
});

test('A.3: grouped widths and gap from frame geometry; hiding a bar returns its share',()=>{
  const f=FX['grouped-bars-line'];
  for(const hidden of [[],['layer:0']]) {
    const {r,hand}=draw(f,640,360,{hidden});
    const n=hidden.length?1:2, width=(hand.band.step*E.tables.look('barShare')-2*(n-1))/n;
    for(const s of r.index.series.filter(s=>s.spans)) for(const b of s.spans) near(b.w,width);
    if(!hidden.length) near(r.index.series[1].spans[0].x-r.index.series[0].spans[0].x-width,2);
  }
});

test('A.4: each layer has its own legend key, switches hide only that layer, minimal has no entries',()=>{
  for(const [w,h,legend] of [[640,360,'top'],[240,140,'bottom'],[80,24,'none']]) {
    const r=draw(FX['four-layers'],w,h).r;
    const entries=r.primitives.filter(p=>p.k==='group'&&p.role==='legend'&&p.legend!==null);
    assert.equal(entries.length,legend==='none'?0:4);
  }
  const all=draw(FX['four-layers']).r, hidden=draw(FX['four-layers'],640,360,{hidden:['layer:0']}).r;
  assert.ok(marks(hidden).every(p=>p.series!=='layer:0'));
  same(hidden.summary.series.map(s=>[s.id,s.title]),all.summary.series.map(s=>[s.id,s.title]));
  assert.equal(hidden.summary.series[0].hidden,true);
});

test('A.5: 2400 interior bar positions select the bar except a nearby line dot; box, anchor and kept card row',()=>{
  const {r}=draw(), model=interaction(r), ms=I.measures(E,'light');
  assert.equal(model.crosshair,true);
  let tested=0, bars=0, lines=0;
  const barPrims=marks(r).filter(p=>p.series==='layer:0');
  for(let j=0;j<barPrims.length;j++) {
    const b=box(barPrims[j]), point=dots(r).find(p=>p.series==='layer:1'&&Math.abs(p.cx-r.index.series[0].at[j])<.011);
    for(let x=0;x<20;x++)for(let y=0;y<20;y++) {
      const pos={x:b.x+b.w*(x+.5)/20,y:b.y+b.h*(y+.5)/20};
      const hit=I.lookup(E,model,pos), wins=point&&Math.hypot(pos.x-point.cx,pos.y-point.cy)<=ms.theme.n('--chart-point')+E.tables.look('crosshairDot');
      assert.equal(hit.mark.id,wins?'layer:1':'layer:0');
      tested++;if(wins)lines++;else bars++;
    }
    const target={kind:'mark',id:'layer:0',datum:j}, sp=r.index.series[0].spans[j];
    const focus=I.targetBox(model,target,ms), anchor=I.anchorOf(model,target.id,j);
    near(focus.x,b.x);near(focus.y,b.y);near(focus.w,b.w);near(focus.h,b.h);near(anchor.x,sp.x+sp.w/2);near(anchor.y,sp.end);
  }
  assert.equal(tested,2400);assert.ok(bars>2300);assert.ok(lines>0);console.log('A.5 interior bar',bars,'line',lines);
  const card=I.cardLines(model,'layer:0',2); assert.ok(JSON.stringify(card).includes('120'));
});

test('A.5 D20: entry, arrows, Home, End follow layer order; last mark takes its own switch, or stays without one',()=>{
  for(const [w,h] of [[640,360],[240,140],[80,24]]) {
    const {r}=draw(FX['four-layers'],w,h), model=interaction(r);
    for (const key of ['Enter', ' ']) same(I.keyTarget(model,null,key), {target:null,toggle:null,handled:false});
    for (const key of ['ArrowRight','ArrowDown','Home','End']) same(I.keyTarget(model,null,key).target,{kind:'mark',id:'layer:0',datum:0});
    let t=I.keyTarget(model,null,'ArrowRight').target;
    assert.deepEqual(t, {kind:'mark',id:'layer:0',datum:0}, 'D20 requires navigation entry from no target to select the first layer mark');
    for(let i=1;i<4;i++){t=I.keyTarget(model,t,'ArrowDown').target;assert.equal(t.id,'layer:'+i);}
    assert.equal(I.keyTarget(model,t,'ArrowDown').target.id,'layer:3');
    t=I.keyTarget(model,t,'ArrowUp').target;assert.equal(t.id,'layer:2');
    t=I.keyTarget(model,t,'ArrowRight').target;assert.equal(t.datum,1);
    t=I.keyTarget(model,t,'ArrowLeft').target;assert.equal(t.datum,0);
    t=I.keyTarget(model,t,'End').target;assert.equal(t.datum,5);
    t=I.keyTarget(model,t,'Home').target;assert.equal(t.datum,0);
    for(const hidden of [[],['layer:0']]) {
      const m=interaction(draw(FX['four-layers'],w,h,{hidden}).r), last={kind:'mark',id:'layer:1',datum:5};
      const next=I.keyTarget(m,last,'ArrowRight').target;
      same(next,w===80?last:{kind:'entry',id:'layer:1'});
    }
  }
});

test('A.2: each aggregate, null category warning and temporal category ordering',()=>{
  for(const [aggregate,expected] of [['sum',8],['mean',4],['min',2],['max',6],['count',2]]) {
    const f=variant('bar-line',(f,e)=>{
      f.columns={m:['B','A','A',null],rev:[4,2,6,99],rate:[.2,.1,.3,.9]};
      e.layers[0].y={type:'quantitative',aggregate,...(aggregate==='count'?{}:{field:'rev'})};
    });
    const {m}=draw(f);assert.equal(m.subs[0].m.cells[0][m.subs[0].m.xs.findIndex(x=>x.raw==='A')].num,expected);
    same(m.warnings.map(w=>w.code),['chart.rule.null-category']);
  }
  const f=variant('temporal-months',(f,e)=>{e.x.sort='ascending';for(const key of Object.keys(f.columns))f.columns[key].reverse();});
  same(draw(f).m.subs[0].m.xs.map(x=>x.raw),[...FX['temporal-months'].columns.m]);
});

test('A.3: a single combo bar is the bar type geometry, defensive domain and log feet stay finite',()=>{
  const {r,hand,h}=draw();
  const f=structuredClone(FX['bar-line']);
  f.document.config.mark={type:'bar'};
  f.document.config.encoding={x:f.document.config.encoding.x,y:f.document.config.encoding.layers[0].y};
  const B=engine(src=>plant(src,'types/bar.js','  R.type("bar",','  R.testBar = {model:model,marks:marks}; R.type("bar",'));
  const bm=B.testBar.model(f.document,f.columns,f.types);
  const br=B.testBar.marks(bm,{...hand,visible:{[bm.series[0].id]:true}},h);
  const expected=marks({primitives:br}).map(box), actual=marks(r).filter(p=>p.series==='layer:0').map(box);
  assert.equal(actual.length,expected.length);
  actual.forEach((b,j)=>{for(const k of ['x','y','w','h'])near(b[k],expected[j][k]);});
  for(const scale of [{domain:[50,400]},{type:'log'}]) {
    const v=variant('bar-line',(_,e)=>{e.layers[0].y.scale=scale;});
    const d=draw(v);
    const expected=d.hand.value.scale(d.hand.value.lo);
    for(const b of d.r.index.series[0].spans) {near(b.foot,expected);assert.ok(Object.values(b).every(Number.isFinite));}
  }
});

test('A.5: card rows and guide dots follow visible non-null layers, preserve units and tooltip details',()=>{
  for(const hidden of [[],['layer:0'],['layer:1']]) {
    const f=variant('bar-line',(f,e)=>{e.layers[0].y.unit='USD';e.tooltip=[{field:'cost',title:'Cost'}];});
    const {r}=draw(f,640,360,{hidden}),model=interaction(r),ms=I.measures(E,'light');
    const id=hidden.includes('layer:0')?'layer:1':'layer:0';
    const card=I.cardLines(model,id,2);
    const text=JSON.stringify(card);
    assert.equal(text.includes('120 USD'),!hidden.includes('layer:0'));
    assert.equal(text.includes('12%'),!hidden.includes('layer:1'));
    assert.ok(text.includes('Cost'));
    const target={kind:'mark',id,datum:2};
    const scene=I.scene(E,model,{frame:{w:640,h:360}},ms,WIDTH,{last:'key',target,hit:null,pointer:null});
    assert.equal(scene.active,id);
    assert.equal(scene.prims.filter(p=>p.k==='circle'&&p.role==='guide').length,hidden.includes('layer:1')?0:1);
  }
  const f=variant('bar-line',f=>f.columns.rate[2]=null),model=interaction(draw(f).r);
  assert.equal(JSON.stringify(I.cardLines(model,'layer:0',2)).includes('Rate'),false);
});


test('A.3: right tick texts are right of the plot, on every left grid line',()=>{
  const {r}=draw(FX['two-lines-two-axes']);
  const plot=r.index.plot, ticks=r.primitives.filter(p=>p.k==='text'&&p.role==='tick');
  const left=ticks.filter(p=>p.anchor==='end'),right=ticks.filter(p=>p.anchor==='start'&&p.x>plot.x+plot.w);
  assert.ok(right.length>=2);assert.equal(right.length,left.length);
  right.forEach(p=>assert.ok(left.some(q=>Math.abs(p.y-q.y)<.011)));
});

test('A.5: two-line card keeps the hovered layer row; focused entry activation toggles its own layer',()=>{
  const r=draw(FX['bar-line'],640,40).r,model=interaction(r),ms=I.measures(E,'light');
  for(const [id,name,value] of [['layer:0','Revenue','120'],['layer:1','Rate','12%']]) {
    const scene=I.scene(E,model,{frame:{w:640,h:40}},ms,WIDTH,{last:'key',target:{kind:'mark',id,datum:2},hit:null,pointer:null});
    same(scene.prims.filter(p=>p.k==='text').map(p=>p.text),['Mar',name,value]);
    for(const key of ['Enter',' '])assert.equal(I.keyTarget(interaction(draw().r),{kind:'entry',id},key).toggle,id);
  }
});

test('A.5: bar-bar, bar-point and bar-area interiors follow drawn boxes and circle reach',()=>{
  let checked=0;
  for(const second of ['bar','point','area']) {
    const f=variant('bar-line',(_,e)=>{e.layers[1].mark=second;});
    const {r}=draw(f),model=interaction(r),ms=I.measures(E,'light');
    for(const ser of r.index.series.filter(s=>s.spans))for(let k=0;k<ser.spans.length;k++) {
      const sp=ser.spans[k],b={x:sp.x,y:Math.min(sp.foot,sp.end),w:sp.w,h:Math.abs(sp.foot-sp.end)};
      for(let x=0;x<20;x++)for(let y=0;y<20;y++) {
        const pos={x:b.x+b.w*(x+.5)/20,y:b.y+b.h*(y+.5)/20};
        // A crosshair gives an area a guide dot at every indexed path point, including before its drawn end dot.
        const points=dots(r).map(d=>({cx:d.cx,cy:d.cy,series:d.series}));
        for(const path of marks(r).filter(p=>p.k==='path'&&p.stroke&&p.fill===null)) for(let j=0;j<path.d.length;) {
          const n=E.prim.ARITY[path.d[j]];
          if(n>=2)points.push({cx:path.d[j+n-1],cy:path.d[j+n],series:path.series});
          j+=n+1;
        }
        const circles=points.filter(d=>Math.abs(d.cx-ser.at[k])<.005).map(d=>({d,distance:Math.hypot(d.cx-pos.x,d.cy-pos.y)})).filter(d=>d.distance<=ms.theme.n('--chart-point')+E.tables.look('crosshairDot')+.0001).sort((a,b)=>a.distance-b.distance);
        const expected=circles.length?circles[0].d.series:ser.id;
        assert.equal(I.lookup(E,model,pos).mark.id,expected,second+' inside '+ser.id);
        checked++;
      }
    }
  }
  assert.equal(checked,9600);console.log('A.5 mixed interiors',checked);
});
