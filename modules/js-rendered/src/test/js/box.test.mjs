// Box's independent numerical, geometry and interaction checks (brief 1072 A.2–A.5).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, MODULE } from "./engine.mjs";
import { expectedBoxMarks, violations } from "./geometry.mjs";
const E = engine(s => {
  const anchor = 'R.type("box", { channels: CHANNELS, model: model, marks: marks,';
  assert.equal(s['types/box.js'].split(anchor).length, 2);
  s['types/box.js'] = s['types/box.js'].replace(anchor, 'R.boxRows = rows; R.boxModel = model; R.type("box", { channels: CHANNELS, model: model, marks: function(m, hand, h) { R.seen = {m, hand}; return marks(m, hand, h); },');
});
const FX = Object.fromEntries(chartFixtures().filter(f=>f.wire==='box').map(f=>[f.name,f.fixture]));
const plain = v => JSON.parse(JSON.stringify(v));
const same = (a,b) => assert.deepEqual(plain(a),plain(b));
const I = createRequire(import.meta.url)(join(MODULE,'../web/src/main/resources/static/js/datapipelines-chart-interactions.js'))._internal;
function fixture(groups, options={}) {
  const f=structuredClone(FX.plain); f.columns={c:[],v:[]};
  for (const [name,values] of Object.entries(groups)) for(const v of values){f.columns.c.push(name);f.columns.v.push(v);}
  Object.assign(f.document.config.mark,options.mark||{});Object.assign(f.document.config.encoding.y,options.y||{});
  if(options.noX) delete f.document.config.encoding.x;
  const values=f.columns.v.filter(v=>v!==null);
  if(values.length&&values.every(v=>typeof v==='string'&&/^-?\d+(?:\.\d+)?$/.test(v))){
    f.types.v=values.some(v=>v.includes('.'))?'BIGDECIMAL':'BIGINTEGER';
    f.document.inputs.result.columns.find(c=>c.name==='v').type=f.types.v;
  }
  return f;
}
function model(f){return E.boxModel(f.document,f.columns,f.types);}
function draw(f,w=960,h=540,extra={}){E.seen=null;const r=E.render(inputOf(f,w,h,extra));return {r,...(E.seen||{})};}
const points = r => r.primitives.filter(p=>p.k==='circle'&&p.role==='mark');
const outsideCount = outs => outs ? (outs > 100 ? 1000 : 100) : 5;
const groupValues = (count,outs) => Object.fromEntries(Array.from({length:count},(_,i)=>['c'+i,Array(outsideCount(outs)).fill(10).concat(Array.from({length:outs},(_,j)=>100+j))]));
let seed=1072;function rand(){seed=(1664525*seed+1013904223)>>>0;return seed/2**32;}
function oracle(values) {
  const s=values.slice().sort((a,b)=>a-b),q=p=>{const h=(s.length-1)*p,i=Math.floor(h),f=h-i;return f?s[i]*(1-f)+s[i+1]*f:s[i];};
  const a=q(.25),b=q(.75),lo=a-1.5*(b-a),hi=b+1.5*(b-a),inside=s.filter(v=>v>=lo&&v<=hi);
  const outside=s.filter(v=>v<lo||v>hi).map((v,i)=>({v,side:v<lo?-1:1,d:v<lo?a-v:v-b,i})).sort((p,q)=>q.d-p.d||p.side-q.side||p.i-q.i).map(o=>o.v);
  return {five:[inside.at(-1),b,q(.5),a,inside[0]],outside};
}
function check(values, expected=oracle(values)) {
  const m=model(fixture({A:values})),c=m.categories[0],s=c.stats;
  same([s.high.num,s.q3.num,s.median.num,s.q1.num,s.low.num],expected.five);
  same(c.points.map(v=>v.num),expected.outside.slice(0,200));
  assert.equal(s.outside.length,expected.outside.length);
  const labels=['Highest inside','Upper quarter','Middle','Lower quarter','Lowest inside'];
  const rows=labels.map((name,i)=>({name,value:E.text.value(expected.five[i])}));
  if(expected.outside.length)rows.push({name:'Outside points',value:E.text.value(expected.outside.length,'integer')});
  same(E.boxRows(m,s),rows);
}

test('A.2 type-7 quartiles, inclusive fences, zero IQR, whiskers and 200 farthest',()=>{
  for(const v of [[1,2,3,4,5,6,7,8,9,10],[1,2,3,4,100],[4,8],[7],[1,1,1,1,9],[5,5,5,5],[1,2,100], [1,2,3,4,5,6,7,8,13],[-3,2,3,4,5,6,7,8,9],[1,2,3,4,5,6,7,8,13.01]])check(v);
  let beyond=0;
  for(let n=0;n<1500;n++){
    const v=Array.from({length:5+Math.floor(rand()*56)},()=>Math.floor(rand()*20));
    if(n%2===0)v.push(1000);const o=oracle(v);if(o.outside.length)beyond++;check(v,o);
  }
  assert.ok(beyond>=500,beyond);console.log({normalSamples:1500,withOutside:beyond});
  const c=model(fixture(groupValues(1,250))).categories[0];
  same(c.points.map(v=>v.num),Array.from({length:200},(_,i)=>349-i));assert.equal(c.stats.outside.length,250);
});

test('A.2 finite opposite extremes and fences overflowing on the way, including 2000 integer-unit oracles',()=>{
  for(const bound of [1e308,Number.MAX_VALUE])check([-bound,bound],{five:[bound,(.75*bound-.25*bound),0,(.25*bound-.75*bound),-bound],outside:[]});
  for(let n=0;n<400;n++){
    const v=Array.from({length:2+Math.floor(rand()*30)},()=>(rand()*2-1)*1.7e308);
    const s=v.slice().sort((a,b)=>a<b?-1:a>b?1:0),q=p=>{const h=(s.length-1)*p,i=Math.floor(h),f=h-i;return f?s[i]*(1-f)+s[i+1]*f:s[i];};
    const c=model(fixture({A:v})).categories[0].stats;same([c.q1.num,c.median.num,c.q3.num],[q(.25),q(.5),q(.75)]);
  }
  const v=[-17,-17,-17,-10,-8,-5,-4,0,17].map(x=>x*1e307);
  check(v,{five:[0,-4e307,-8e307,-1.7e308,-1.7e308],outside:[1.7e308]});
  check(v.map(x=>-x),{five:[1.7e308,1.7e308,8e307,4e307,0],outside:[-1.7e308]});
  const unit=2**1014;let overflowOutside=0;
  for(let n=0;n<2000;n++){
    const units=Array.from({length:2+Math.floor(rand()*30)},()=>Math.floor(rand()*2001)-1000),o=oracle(units);
    const actual=units.map(x=>x*unit),c=model(fixture({A:actual})).categories[0];
    same([c.stats.high.num,c.stats.q3.num,c.stats.median.num,c.stats.q1.num,c.stats.low.num],o.five.map(x=>x*unit));
    same(c.points.map(v=>v.num),o.outside.slice(0,200).map(x=>x*unit));
    const q1=c.stats.q1.num,q3=c.stats.q3.num;
    if(o.outside.length&&(!Number.isFinite(q1-1.5*(q3-q1))||!Number.isFinite(q3+1.5*(q3-q1)))){
      overflowOutside++;
      // Amplify a missed outside value into the total cap: six categories, 200 copies per value.
      const capCase=fixture(Object.fromEntries(Array.from({length:6},(_,i)=>['c'+i,actual.flatMap(v=>Array(200).fill(v))])));
      const wanted=6*Math.min(200,oracle(units.flatMap(v=>Array(200).fill(v))).outside.length)>1000?0:6;
      assert.equal(expectedBoxMarks(capCase),wanted,'I3 must count points beyond an overflowing intermediate fence');
    }
    assert.equal(expectedBoxMarks(fixture({A:actual})),1);
  }
  assert.ok(overflowOutside>50,overflowOutside);console.log({unitSamples:2000,overflowOutside});
  for(const x of [750,751,Number.MAX_VALUE/unit]){
    const a=[-1000,-1000,-1000,-800,-600,-400,-300,0,x].map(v=>v*unit),s=model(fixture({A:a})).categories[0].stats;
    assert.equal(s.outside.length,x===750?0:1);
  }
});

test('A.2 exact 15, 18, 24-digit and decimal quartiles, distances and capped point order',()=>{
  for(const digits of [15,18,24])for(const places of [0,2,3])for(let sample=0;sample<150;sample++){
    const base=10n**BigInt(digits),offset=Array.from({length:5+Math.floor(rand()*30)},()=>Math.floor(rand()*30));if(sample%2===0)offset.push(1000);
    const raw=offset.map(v=>{const x=String(base+BigInt(v));return places?x.slice(0,-places)+'.'+x.slice(-places):x;});
    const c=model(fixture({A:raw})),s=c.categories[0].stats,sorted=offset.slice().sort((a,b)=>a-b);
    const quarter=k=>{const h=(sorted.length-1)*k,i=Math.floor(h/4),f=h%4;return BigInt(sorted[i])*(4n-BigInt(f))+BigInt(sorted[Math.min(i+1,sorted.length-1)])*BigInt(f);};
    for(const [k,name] of [[1,'q1'],[2,'median'],[3,'q3']]){
      const exact=s[name].exact, [whole,fraction='']=exact.split('.'),n=BigInt(whole+fraction),scale=10n**BigInt(fraction.length);
      assert.equal(n*4n*10n**BigInt(places),(base*4n+quarter(k))*scale);
    }
    assert.equal(s.outside.length,oracle(offset).outside.length);
  }
  const base=10n**24n,text=n=>String(base+BigInt(n));
  let c=model(fixture({A:Array(1000).fill(text(0)).concat(Array.from({length:250},(_,i)=>text(100+i)))})).categories[0];
  same(c.points.map(v=>v.exact),Array.from({length:200},(_,i)=>text(349-i)));
  c=model(fixture({A:Array(1000).fill(text(0)).concat(Array.from({length:150},(_,i)=>text(-100-i)),Array.from({length:150},(_,i)=>text(100+i)))})).categories[0];
  same(c.points.map(v=>v.exact),Array.from({length:100},(_,i)=>[text(-249+i),text(249-i)]).flat());
});

test('A.2 warnings once, no-data, options, caps and direct I3 on refusals and exact columns',()=>{
  same(model(fixture({A:[null,null],B:[null]})).categories,[]);
  const f=fixture({A:[null,1,2,1e999,1e999],B:[null]});f.columns.c.push(null,null);f.columns.v.push(1,2);
  same(model(f).warnings.map(w=>w.code),['chart.rule.non-finite','chart.rule.null-category']);
  const log=fixture({A:[-1,0,1,2]}, {y:{scale:{type:'log'}}});same(model(log).warnings.map(w=>w.code),['chart.rule.log-nonpositive']);
  assert.throws(()=>model(fixture({A:[true]})),/wire form/);assert.throws(()=>model(fixture({A:['bad']})),/wire form/);
  for(const [boxes,outside,want] of [[5,200,5],[5,201,5],[6,200,0],[50,21,0],[50,0,50],[51,0,0]]){
    const f=fixture(groupValues(boxes,outside));assert.equal(expectedBoxMarks(f),want);const d=draw(f);assert.equal(d.r.summary.marks,want);
  }
  for(const [boxes,want] of [[5,5],[6,0]])for(const kind of ['integer','decimal','tiny']){
    const f=fixture(groupValues(boxes,200));f.columns.v=f.columns.v.map(v=>kind==='integer'?String(10n**24n+BigInt(v)):kind==='decimal'?v+'.0':'0.'+String(v).padStart(20,'0'));
    f.types.v=kind==='integer'?'BIGINTEGER':'BIGDECIMAL';
    assert.equal(expectedBoxMarks(f),want);assert.equal(draw(f).r.summary.marks,want);
  }
  const none=fixture(groupValues(6,200),{mark:{outliers:'none'}});assert.equal(expectedBoxMarks(none),6);const d=draw(none);assert.equal(points(d.r).length,0);assert.equal(d.m.categories[0].stats.outside.length,200);assert.equal(d.hand.value.hi,10);
  for(const f of Object.values(FX))assert.equal(expectedBoxMarks(f),draw(f).r.summary.marks);
});

const FRAMES=[[80,24],[120,60],[240,100],[480,320],[960,540],[1600,480]];
test('A.3 body and lookup bounds, median, circle ring fits column/plot, tokens and independent invariants',()=>{
  const edges=[[5],Array(8).fill(5),[1,1,1,1,10],[-10,1,1,1,1,1,10],[1,1,1,1,1.01],Array(12).fill(1).concat([50,50,50])];
  const fixtures=Object.values(FX).concat(edges.map(v=>fixture({A:v})),[fixture(Object.fromEntries(Array.from({length:50},(_,i)=>['c'+i,[-10,1,1,1,1,1,10]]))),fixture({A:[.1,1,1,1,1,1,100]},{y:{scale:{type:'log'}}}),fixture({A:[5,5,5,5]},{y:{scale:{domain:[0,5]}}}),fixture({A:[5,5,5,5]},{y:{scale:{domain:[5,100]}}}),fixture({A:[200,300,400]},{y:{scale:{domain:[0,100]}}}),fixture({A:[-400,-300,-200]},{y:{scale:{domain:[0,100]}}}),fixture({A:[1,1,1,1,1.01]},{y:{scale:{domain:[0,100]}}})]);
  let floor=0,scaled=0,touching=0,seen=0;
  for(const f of fixtures)for(const [w,h] of FRAMES)for(const line of [0,2,8])for(const point of [1,4,16])for(const gap of [0,2,8]){
    const d=draw(f,w,h,{theme:{base:'light',tokens:{'--chart-line':line,'--chart-point':point,'--chart-gap':gap}}});if(d.r.state!=='rendered')continue;
    const {hand,r}=d,p=hand.plot,tol=.021,inside=(v,a,b)=>assert.ok(v>=a-tol&&v<=b+tol,`${f.display_name||f.note}: ${v} outside ${a}..${b}`);
    same(violations(r.primitives,w,h).violations,[]);
    const width=Math.min(56,.5*hand.band.step),least=Math.min(3*line,p.h);
    for(const box of r.index.marks.filter(b=>b.shape.kind==='box')){
      seen++;const s=box.shape;inside(s.x,p.x,p.x+p.w);inside(s.x+s.w,p.x,p.x+p.w);inside(s.y,p.y,p.y+p.h);inside(s.y+s.h,p.y,p.y+p.h);assert.ok(Math.abs(s.w-width)<tol);assert.equal(box.row,0);
      const prims=r.primitives.filter(v=>v.role==='mark'&&v.datum===box.datum&&v.k!=='g');assert.equal(prims.length,5);assert.equal(prims[4].stroke,'--chart-canvas');assert.equal(prims[0].round,true);
      const b=prims[3];
      // The four-arc body exposes its top/bottom as the first horizontal line and the bottom horizontal line.
      const top=b.k==='rect'?b.y:b.d[2],bottom=b.k==='rect'?b.y+b.h:b.d[19];
      inside(top,p.y,p.y+p.h);inside(bottom,p.y,p.y+p.h);assert.ok(bottom-top>=least-tol);inside(prims[4].d[2],top,bottom);
      if(Math.abs(bottom-top-least)<tol)floor++;
    }
    for(const c of points(r)){
      const idx=Number(c.datum.split('/')[0]),cx=hand.band.start+hand.band.step*(idx+.5),outer=c.r+c.strokeWidth/2;
      inside(c.cx-outer,p.x,p.x+p.w);inside(c.cx+outer,p.x,p.x+p.w);inside(c.cy-outer,p.y,p.y+p.h);inside(c.cy+outer,p.y,p.y+p.h);inside(c.cx-outer,cx-width/2,cx+width/2);inside(c.cx+outer,cx-width/2,cx+width/2);
      if(c.r<Math.max(0,point-1)-.01)scaled++;if(Math.abs(c.cy-outer-p.y)<tol||Math.abs(c.cy+outer-p.y-p.h)<tol)touching++;
      const entry=r.index.marks.find(m=>m.datum===c.datum);same(entry.shape,{kind:'point',x:c.cx,y:c.cy,r:c.r});assert.equal(entry.key,false);assert.equal(entry.near,false);
      if(c.r>0)assert.equal(r.index.marks[E.lookup.at(r.index,c.cx,c.cy,24)].shape.kind,'point');
    }
  }
  assert.ok(seen>1000);assert.ok(floor>0);assert.ok(scaled>0);assert.ok(touching>0);console.log({seen,floor,scaled,touching});
});

test('A.4 circle precedence, box-width hit shape, no near reach, row-zero keyboard walk and independent cards',()=>{
  const ordinary=draw(FX.plain).r,box=ordinary.index.marks[0].shape;
  for(const [x,y] of [[box.x+box.w/2,box.y+box.h/2],[box.x+box.w/2,box.y+1],[box.x+1,box.y+box.h/2]])assert.equal(E.lookup.at(ordinary.index,x,y,24),0);
  const out=ordinary.index.marks.find(e=>e.shape.kind==='point').shape;
  assert.equal(E.lookup.at(ordinary.index,out.x,out.y+20,24),-1);
  const zero=draw(FX['flat-iqr'],80,24,{theme:{base:'light',tokens:{'--chart-point':1}}}).r,p0=zero.index.marks.find(e=>e.shape.kind==='point').shape;
  assert.equal(p0.r,0);assert.equal(zero.index.marks[E.lookup.at(zero.index,p0.x,p0.y,24)].shape.kind,'point');
  assert.notEqual(zero.index.marks[E.lookup.at(zero.index,p0.x+.1,p0.y,24)]?.shape.kind,'point');
  const f=fixture({North:[1,1,1,1,1.01],South:[2,3,4],East:[5,6,7]},{y:{scale:{domain:[0,100]}}});
  for(const [w,h] of [[480,320],[80,24]]){
    const {r}=draw(f,w,h),m=I.modelOf(E,r,E.measure.measurer(null).fn),entries=r.index.marks;
    const point=entries.find(e=>e.shape.kind==='point'),b=entries[0].shape;
    assert.equal(E.lookup.at(r.index,point.shape.x,point.shape.y,24),entries.indexOf(point));
    assert.equal(E.lookup.at(r.index,b.x-5,b.y+b.h/2,24),-1);
    const target=n=>({kind:'mark',id:'measure',datum:n});
    for(const [from,key,want] of [[0,'ArrowRight',1],[1,'ArrowRight',2],[2,'ArrowRight',2],[2,'ArrowLeft',1],[2,'Home',0],[0,'End',2],[1,'ArrowDown',1],[1,'ArrowUp',1]])assert.equal(I.keyTarget(m,target(from),key).target.datum,want);
    same(entries.map(e=>e.row),[0,0,0,0]);assert.equal(entries.filter(e=>e.key).length,3);
    const boxLines=I.markLines(m,0),pointLines=I.markLines(m,entries.indexOf(point));
    same(boxLines.map(l=>l.kind==='heading'?l.text:[l.name,l.value]),['North',['Highest inside','1'],['Upper quarter','1'],['Middle','1'],['Lower quarter','1'],['Lowest inside','1'],['Outside points','1']]);
    assert.equal(boxLines.find(l=>l.kept).name,'Middle');assert.equal(pointLines.find(l=>l.kept).name,'This point');
    const measures=I.measures(E,'light',E.measure.measurer(null).fn),width=E.measure.measurer(null).fn;
    for(const [lines,kept,more] of [[boxLines,'Middle','+5 more'],[pointLines,'This point','+6 more']]){
      const card=I.markCard(E,width,lines,null,{w,h},measures);
      if(h===24){assert.equal(card.k,1);assert.equal(card.lines.length,1);assert.equal(card.lines[0].line.name,kept);}
      else same(card.lines.map(l=>l.line),lines);
      const small=I.markCard(E,width,lines,null,{w:480,h:Math.ceil(2*measures.p+3*measures.h)},measures);
      assert.equal(small.k,3);same(small.lines.map(l=>l.line.kind==='heading'?l.line.text:l.line.kind==='more'?l.text:l.line.name),['North',kept,more]);
    }
    const ring=I.ringPrims(E,m,entries.indexOf(point),measures);assert.equal(ring[0].k,'circle');assert.equal(ring[0].r,point.shape.r+3);
  }
});

test('A.4 box row ends retain shortened-text keyboard access and otherwise stop',()=>{
  const fullLabel='A very long category name that keeps going and going';
  const target=datum=>({kind:'mark',id:'measure',datum});
  for(const name of ['plain','long-labels'])for(const [w,h] of [[80,24],[480,320],[960,540]]){
    const {r}=draw(FX[name],w,h),m=I.modelOf(E,r,E.measure.measurer(null).fn);
    const keys=r.index.marks.filter(e=>e.key);
    same(keys.map(e=>[e.datum,e.row,e.shape.kind]),[[0,0,'box'],[1,0,'box'],[2,0,'box']]);
    assert.ok(r.index.marks.filter(e=>e.shape.kind==='point').every(e=>e.key===false));
    for(const [from,key,want] of [[0,'ArrowLeft',0],[0,'ArrowRight',1],[1,'ArrowRight',2],[2,'ArrowLeft',1],[2,'Home',0],[0,'End',2],[1,'ArrowDown',1],[1,'ArrowUp',1]])same(I.keyTarget(m,target(from),key).target,target(want));
    const next=I.keyTarget(m,target(2),'ArrowRight').target;
    if(name==='long-labels'&&h>24){
      assert.equal(m.cuts.length,2);same(next,{kind:'cut',n:0});
      assert.equal(m.cuts[next.n].text,fullLabel);
      same(I.keyTarget(m,next,'ArrowLeft').target,target(2));
    }else{
      assert.equal(m.cuts.length,0);same(next,target(2));
    }
  }
});

test('A.5 direct oracle at 1000/1001, exact 20-decimal and 25-digit caps and boxes-first refusal',()=>{
  const groups=groupValues(5,200);groups.last=Array(1000).fill(10).concat([100]);
  const exactEdge=Object.fromEntries(Object.entries(groups).map(([k,v])=>[k,v.map(n=>'10.'+String(n).padStart(20,'0'))]));
  assert.equal(expectedBoxMarks(fixture(exactEdge)),0);assert.equal(draw(fixture(exactEdge)).r.summary.marks,0);
  for(const [f,want] of [[fixture(groups),0],[fixture(groupValues(51,24)),0],[fixture(groupValues(6,200),{mark:{outliers:'none'}}),6]]){
    assert.equal(expectedBoxMarks(f),want);assert.equal(draw(f).r.summary.marks,want);
  }
  for(const [count,want] of [[5,5],[6,0]])for(const kind of ['decimal','integer']){
    const f=fixture(groupValues(count,200));
    f.columns.v=f.columns.v.map(v=>kind==='decimal'?'10.'+String(v).padStart(20,'0'):String(10n**24n+BigInt(v)));
    f.types.v=kind==='integer'?'BIGINTEGER':'BIGDECIMAL';
    assert.equal(expectedBoxMarks(f),want);assert.equal(draw(f).r.summary.marks,want);
  }
  const f=fixture(groupValues(5,200));f.columns.v=f.columns.v.map(v=>v+'.0');
  assert.equal(expectedBoxMarks(f),5);
  f.columns.c.push('sixth');f.columns.v.push('10.00000000000000000000');
  // A sixth category with one value is not an outside point, and stays below the point cap.
  assert.equal(expectedBoxMarks(f),6);
  const refused=draw(fixture(groupValues(51,24))).r;
  assert.equal(refused.state,'refused');same(refused.summary.refusal.details,{marks:51,limit:50});
});

test('A.3 paints, cap geometry, axes, ordering and token consumers from independent expectations',()=>{
  const f=fixture({A:[1,2,3,4,100]});
  for(const [w,h] of [[240,140],[480,320],[960,540],[1600,480]]){
    const {r,hand}=draw(f,w,h),ps=r.primitives.filter(p=>p.role==='mark'&&p.k!=='group'),width=Math.min(56,hand.band.step*.5);
    for(const p of ps.slice(0,3)){assert.equal(p.stroke,'--chart-series-1');assert.equal(p.strokeWidth,2);assert.equal(p.round,true);}
    assert.ok(Math.abs(ps[1].d[4]-ps[1].d[1]-.44*width)<.021);
    assert.equal(ps[3].fill,'--chart-series-1');assert.equal(ps[3].stroke,null);
    assert.equal(ps[4].stroke,'--chart-canvas');assert.equal(ps[4].round,false);
    const circle=points(r)[0];assert.equal(circle.r,3);assert.equal(circle.strokeWidth,1.5);assert.equal(circle.opacity,.75);
    const top=ps[3].d[2],bottom=ps[3].d[19];assert.equal(ps[3].d[7],Math.min(4,width/2,(bottom-top)/2));
  }
  const a=model(f);same(a.layout.extent({measure:true}),[1,100]);assert.equal(a.layout.bandTitle,'c');assert.equal(a.layout.valueTitle,'v');
  same(model(fixture({A:[10,10,10,10,100]},{mark:{outliers:'none'}})).layout.extent({measure:true}),[10,10]);
  same(model(fixture({A:[1,2,3]},{y:{scale:{zero:true}}})).layout.extent({measure:true}),[0,3]);
  const titled=fixture({A:[1,2,3]},{noX:true,y:{title:'Height',unit:'cm',axis:{grid:false},scale:{type:'log',domain:[1,200]}}});
  same(draw(titled).r.index.marks[0].card.rows.map(r=>r.value),['3 cm','2.5 cm','2 cm','1.5 cm','1 cm']);
  const m=model(titled);assert.equal(m.layout.bandTitle,null);assert.equal(m.layout.valueTitle,'Height (cm)');assert.equal(m.layout.log,true);same(m.layout.domain,[1,200]);assert.equal(m.layout.valueGrid,false);
  assert.equal(model(fixture({A:[1]},{y:{title:null,unit:'cm'}})).layout.valueTitle,null);
  for(const [sort,want] of [[null,['B','A','C']],['ascending',['A','B','C']],['descending',['C','B','A']],[['C','B','A'],['C','B','A']]]){
    const f=fixture({B:[1],A:[2],C:[3]});f.document.config.encoding.x.sort=sort;same(model(f).categories.map(c=>c.full),want);
  }
  const three=fixture({A:Array(12).fill(1).concat([50,50,50])}),r=draw(three).r,p=points(r)[0];
  assert.equal(r.index.marks[E.lookup.at(r.index,p.cx,p.cy,24)].datum,'0/2');
  const single=draw(fixture({A:[1,1,1,1,1.01]},{y:{scale:{domain:[0,100]}}})).r,point=points(single)[0];
  assert.equal(single.index.marks[E.lookup.at(single.index,point.cx+point.r-.1,point.cy,24)].datum,'0/0');
  assert.equal(single.index.marks[E.lookup.at(single.index,point.cx+point.r+.1,point.cy,24)].datum,0);
});
