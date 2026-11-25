// Heatmap's own checks (brief 1070 A.2–A.5). Expected geometry is derived from the handed slots, not the marks.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, MODULE } from "./engine.mjs";
import { expectedHeatmapMarks, WIDTHS, HEIGHTS } from "./geometry.mjs";
import { plant } from "./forms.mjs";
const FILE = "types/heatmap.js";
const REGISTER = 'R.type("heatmap", { channels: CHANNELS, model: model, marks: marks,';
const E = engine((s) => plant(s, FILE, REGISTER, 'R.heatModel = model; R.type("heatmap", { channels: CHANNELS, model: model, marks: function(m, hand, h) { R.seen = {m, hand, h}; return marks(m, hand, h); },'));
const I = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const FX = Object.fromEntries(chartFixtures().filter((f) => f.wire === "heatmap").map((f) => [f.name, f.fixture]));
const plain = (v) => JSON.parse(JSON.stringify(v));
const same = (a,b) => assert.deepEqual(plain(a),plain(b));
const rects = (r) => r.primitives.filter((p) => p.role === "mark");
const variant = (name, edit) => { const f = structuredClone(FX[name]); edit(f, f.document.config.encoding); return f; };
function draw(f, w=960, h=540, extra={}) { E.seen=null; const r=E.render(inputOf(f,w,h,extra)); return {r,...E.seen}; }
const model = (r) => I.modelOf(E,r,E.measure.measurer(null).fn);
const target = (datum) => ({kind:"mark",id:null,datum});
const key = (m,datum,k) => I.keyTarget(m,datum===null?null:target(datum),k).target;
const close = (a,b,tol=0.010000001) => assert.ok(Math.abs(a-b)<=tol, `${a} != ${b}`);
const sparse = (n,values=1) => variant("single", (f) => { f.columns={x:Array.from({length:n},(_,i)=>'x'+i), y:Array.from({length:n},(_,i)=>'y'+i), v:Array.from({length:n},(_,i)=>i<values?5:null)}; });

test("A.2 aggregates count non-null values; empty pairs, zeros, typed keys, exact values and warnings",()=>{
  for(const [aggregate,value] of [["sum",7],["mean",7/3],["min",1],["max",4],["count",3]]) {
    const f=variant("single",(f,e)=>{e.color.aggregate=aggregate;f.columns={x:['a','a','a','a'],y:['r','r','r','r'],v:[1,2,null,4]};});
    close(draw(f).m.cells[0].agg.num,value,1e-9);
    assert.equal(draw(variant("all-null",(f,e)=>e.color.aggregate=aggregate)).r.state,'no-data');
  }
  for(const name of Object.keys(FX)) {
    const {r,m}=draw(FX[name]); if(r.state!=='rendered')continue;
    assert.equal(r.summary.marks,expectedHeatmapMarks(FX[name]),name);
    assert.equal(m.markCount,r.summary.marks); same(r.summary.series,[]);same(r.index.series,[]);
    assert.equal(new Set(r.index.marks.map(e=>e.series+'@'+e.datum)).size,r.summary.marks);
    for(const c of m.cells) assert.equal(c.datum,c.r*m.cols.length+c.c);
  }
  const typed=draw(variant('single',f=>f.columns={x:[1,'1',1],y:['r','r','r'],v:[0,2,3]}));
  assert.equal(typed.m.cols.length,2);same(typed.m.cells.map(c=>c.agg.num),[3,2]);
  const warn=draw(variant('single',f=>f.columns={x:[null,null,'a','a','b','b','c'],y:['r',null,'r','r','r','r','r'],v:[1,2,1e308,1e308,1e308,1e308,0]}));
  same(warn.r.summary.warnings.map(w=>w.code),['chart.rule.null-category','chart.rule.non-finite']);assert.equal(warn.r.summary.marks,1);
  assert.equal(draw(FX['cancel-exact']).r.summary.marks,2);assert.equal(draw(FX['cancel-float']).r.summary.marks,1);
});

test("A.2 orders, first y at top, scale ends, ramp steps, legend placement and caps",()=>{
  const f=variant('single',f=>f.columns={x:[100,9,10],y:['c','a','b'],v:[-7,0,12]});
  for(const [sort,want] of [[null,[100,9,10]],['ascending',[9,10,100]],['descending',[100,10,9]],[[10,100,9],[10,100,9]]]) {
    const d=draw(variantOf(f,e=>{e.x.sort=sort;e.y.sort='ascending';}));same(d.m.cols.map(c=>c.raw),want);same(d.m.rows.map(c=>c.raw),['a','b','c']);
  }
  for(const [name,ends] of [['diverging',['−17','17']],['diverging-mid',['−10','34']],['flat',['5','5']]]) {
    const d=draw(FX[name]);same([d.m.shade.scale.loText,d.m.shade.scale.hiText],ends);
  }
  for(const scale of ['sequential','diverging']) {
    const d=draw(variant('diverging',(f,e)=>e.color.scale=scale));assert.equal(new Set(rects(d.r).map(p=>p.fill)).size,7);
  }
  const top=draw(FX['grid-4x3']), none=draw(FX['legend-none']);assert.ok(top.hand.plot.y>none.hand.plot.y);
  assert.equal(draw(FX['cap-1000']).r.summary.marks,1000);
  const refused=draw(FX['thousand-one']).r;assert.equal(refused.summary.refusal.code,'chart.too_many_marks');same(refused.summary.refusal.details,{marks:1001,limit:1000});
  assert.equal(draw(sparse(20001)).r.summary.refusal.code,'chart.too_many_rows');
});
function variantOf(f,edit) { const c=structuredClone(f);edit(c.document.config.encoding);return c; }

test("A.2 sparse model Map work is bounded by rows, never the category grid",()=>{
  const C=engine(s=>{
    plant(s,FILE,REGISTER,'R.heatModel = model; '+REGISTER);
    s[FILE]+='\nvar HMCalls = 0; ["get","has","set"].forEach(function(k) { var original = Map.prototype[k]; Map.prototype[k] = function() { HMCalls++; return original.apply(this, arguments); }; }); JsRendered.hmCalls = function() { return HMCalls; };';
  });
  for(const values of [1,20000]) {
    const f=sparse(20000,values), before=C.hmCalls();C.heatModel(f.document,f.columns,f.types);const calls=C.hmCalls()-before;
    assert.ok(calls>=20000 && calls<=800000,'Map calls '+calls);console.log('A.2 sparse values='+values+' Map calls='+calls);
    const r=C.render(inputOf(f,80,24));assert.equal(r.state,values===1?'rendered':'refused');
    if(values===1)assert.equal(r.summary.marks,1);else same(r.summary.refusal.details,{marks:20000,limit:1000});
  }
});

function checkGeometry(d) {
  const {r,m,hand,h}=d, plot=hand.plot, rs=rects(r);
  assert.equal(rs.length,m.cells.length);
  for(let i=0;i<rs.length;i++) {
    const p=rs[i], c=m.cells[i], box=r.index.marks[i].shape;
    assert.equal(p.datum,c.r*m.cols.length+c.c);assert.equal(r.index.marks[i].row,c.r);
    for(const [axis,step,start,at,size,ba,bs,edge] of [
      ['x',hand.band.step,hand.band.start+c.c*hand.band.step,p.x,p.w,box.x,box.w,plot.x+plot.w],
      ['y',hand.rows.step,hand.rows.start+c.r*hand.rows.step,p.y,p.h,box.y,box.h,plot.y+plot.h]]) {
      const gap=Math.min(h.theme.n('--chart-gap'),E.tables.look('cellGapMaxShare')*step)/2;
      const raw=E.scales.r2(step-2*gap), rawBox=E.scales.r2(step);
      assert.ok(size>=0.01 && bs>=0.01,axis+' side floor');close(size,Math.max(0.01,raw),1e-9);close(bs,Math.max(0.01,rawBox),1e-9);
      close(at,raw<0.01?E.scales.r2(Math.min(E.scales.r2(start+gap),edge-0.01)):E.scales.r2(start+gap),1e-9);
      close(ba,rawBox<0.01?E.scales.r2(Math.min(E.scales.r2(start),edge-0.01)):E.scales.r2(start),1e-9);
      assert.ok(at+size<=edge+0.010000001 && ba+bs<=edge+0.010000001);
    }
    assert.ok(p.x>=plot.x-0.010000001 && p.y>=plot.y-0.010000001);
    close(p.rx,E.scales.r2(Math.min(h.theme.n('--chart-cell-radius'),p.w/2,p.h/2)),1e-9);
    assert.equal(p.fill,E.colour.token(m.shade.scale,c.agg.num));
  }
  return rs.length;
}

test("A.3 geometry over every fixture and sweep frame; gap/radius tokens; dense floors on rectangles AND boxes",()=>{
  let cells=0;
  for(const f of Object.values(FX))for(const w of [...WIDTHS,80])for(const ht of [...HEIGHTS,24]) {
    const d=draw(f,w,ht);if(d.r.state==='rendered')cells+=checkGeometry(d);
  }
  for(const radius of [0,8,32])for(const gap of [0,8])checkGeometry(draw(FX['narrow-cells'],80,24,{theme:{base:'light',tokens:{'--chart-cell-radius':radius,'--chart-gap':gap}}}));
  for(const n of [2000,20000])for(const frame of [[80,24],[480,320]])checkGeometry(draw(sparse(n),...frame));
  const d=draw(sparse(20000),80,24), p=rects(d.r)[0],b=d.r.index.marks[0].shape;
  same([p.w,p.h,b.w,b.h],[0.01,0.01,0.01,0.01]);close(p.x,d.hand.plot.x);close(p.y,d.hand.plot.y);
  checkGeometry(draw(sparse(20000,1000),80,24));assert.ok(cells>300000);console.log('A.3 cells checked='+cells);
});

const luminance = rgb => rgb.reduce((sum,c,i)=>sum+[0.2126,0.7152,0.0722][i]*(c/255<=0.04045?c/255/12.92:((c/255+0.055)/1.055)**2.4),0);
const contrast = (a,b) => (Math.max(luminance(a),luminance(b))+0.05)/(Math.min(luminance(a),luminance(b))+0.05);
test("A.4 labels fit, equal card values, follow authored/default options and never occur at minimal; LA16 inks",()=>{
  let labels=0;
  for(const f of Object.values(FX))for(const [w,ht] of [[960,540],[360,240],[200,140],[80,24]]) {
    const d=draw(f,w,ht);if(d.r.state!=='rendered')continue;
    const texts=d.r.primitives.filter(p=>p.role==='guide'&&p.k==='text');
    if(!d.m.labelsOn||d.r.level==='minimal')assert.equal(texts.length,0);
    for(const t of texts) {
      const p=rects(d.r).find(p=>Math.abs(p.x+p.w/2-t.x)<0.011 && t.x-d.h.width(t.text,t.size)/2>=p.x-0.011 && t.y-E.tables.look('textAscent')*t.size>=p.y-0.011 && t.y+E.tables.look('textDescent')*t.size<=p.y+p.h+0.011);
      assert.ok(p,'label inside a cell');assert.ok(d.h.width(t.text,d.h.small)<=p.w-2*E.tables.look('valueLabelGap')*d.h.small+1e-9);
      assert.equal(t.text,d.r.index.marks.find(e=>e.datum===p.datum).card.rows[0].value);labels++;
    }
  }
  for(const theme of ['light','dark'])for(const scale of ['sequential','diverging']) {
    const f=variant('single',(f,e)=>{f.columns={x:[0,1,2,3,4,5,6],y:Array(7).fill('r'),v:[-3,-2,-1,0,1,2,3]};e.color.scale=scale;f.document.config.mark.labels='values';});
    const d=draw(f,960,540,{theme}), ts=d.r.primitives.filter(p=>p.role==='guide'&&p.k==='text');assert.equal(ts.length,7);
    const th=d.h.theme, light=th.colour('--chart-on-fill-light'), dark=th.colour('--chart-on-fill-dark');
    for(let i=0;i<7;i++) {const fill=th.colour(rects(d.r)[i].fill), want=contrast(fill,light)>=contrast(fill,dark)?'--chart-on-fill-light':'--chart-on-fill-dark'; assert.equal(ts[i].fill,want);assert.ok(Math.max(contrast(fill,light),contrast(fill,dark))>=4.29);}
  }
  assert.ok(labels>100);console.log('A.4 fitted labels='+labels);
});

test("A.5 slot lookup, gap, empty slots, ring/card and row-major key plan including minimal",()=>{
  let empty=0;
  for(const f of Object.values(FX)) {
    const d=draw(f,480,320);if(d.r.state!=='rendered')continue;const lm=model(d.r), measures=I.measures(E,'light');
    for(let i=0;i<d.r.index.marks.length;i++) {
      const e=d.r.index.marks[i],b=e.shape;assert.equal(e.key,true);assert.equal(e.emphasis,'ring');assert.equal(e.card.rows.length,1);assert.equal(e.card.keep,0);same(e.card.extra,[]);
      assert.equal(E.lookup.at(d.r.index,b.x+b.w/2,b.y+b.h/2,24),i);
      const ring=I.ringPrims(E,lm,i,measures);assert.equal(ring.length,1);assert.equal(ring[0].stroke,rects(d.r)[i].fill);assert.equal(I.moveOf(E,lm,i),null);
      assert.equal(I.markLines(lm,i)[0].text,e.card.heading);
    }
    const occupied=new Set(d.m.cells.map(c=>c.datum));
    if(d.hand.band.step>=0.03&&d.hand.rows.step>=0.03)for(let r=0;r<d.m.rows.length;r++)for(let c=0;c<d.m.cols.length;c++)if(!occupied.has(r*d.m.cols.length+c)) {
      assert.equal(E.lookup.at(d.r.index,d.hand.band.start+(c+0.5)*d.hand.band.step,d.hand.rows.start+(r+0.5)*d.hand.rows.step,24),-1);empty++;
    }
    assert.equal(I.lookup(E,lm,{x:d.hand.plot.x-1,y:d.hand.plot.y-1}).mark,null);
  }
  assert.ok(empty>3900000);console.log('A.5 empty slot middles='+empty);
  for(const frame of [[960,540],[80,24]]) {
    const d=draw(FX['grid-4x3'],...frame), lm=model(d.r);assert.equal(lm.cuts.length,0);
    for(const [from,k,want] of [[null,'ArrowRight',0],[0,'ArrowRight',1],[3,'ArrowRight',3],[0,'ArrowLeft',0],[1,'ArrowDown',5],[5,'ArrowUp',1],[1,'ArrowUp',1],[6,'Home',4],[5,'End',7]])assert.equal(key(lm,from,k).datum,want);
    let t=null;const visited=[];
    for(const k of ['ArrowRight','ArrowRight','ArrowRight','ArrowRight','ArrowDown','ArrowDown','ArrowDown','ArrowLeft','Home','End','ArrowUp']) {t=I.keyTarget(lm,t,k).target;const n=d.r.index.marks.findIndex(e=>e.datum===t.datum);visited.push([t.datum,plain(I.markLines(lm,n))]);assert.equal(I.ringPrims(E,lm,n,I.measures(E,'light')).length,1);}
    same(visited.map(v=>v[0]),[0,1,2,3,7,11,11,10,8,11,7]);console.log('KEY PLAN '+frame+' '+JSON.stringify(visited));
  }
  const d=draw(FX['missing-cells']),lm=model(d.r);for(const [from,k,want] of [[0,'ArrowRight',1],[0,'ArrowDown',5],[2,'ArrowDown',7],[17,'ArrowDown',17]])assert.equal(key(lm,from,k).datum,want);
});

test("A.5 dense overlap follows boxes and last containment, and shortened-text exit follows the shared layer",()=>{
  for(const frame of [[80,24],[480,320]]) {
    const d=draw(sparse(20000),...frame),lm=model(d.r), pos=(c,r)=>({x:d.hand.band.start+(c+0.5)*d.hand.band.step,y:d.hand.rows.start+(r+0.5)*d.hand.rows.step});
    const p=pos(1,1), got=E.lookup.at(d.r.index,p.x,p.y,24);assert.equal(got,frame[0]===80?0:-1);
    assert.equal(I.lookup(E,lm,p).mark?.datum ?? null,frame[0]===80?0:null);
    const b=d.r.index.marks[0].shape, c=Math.ceil((b.x+b.w-d.hand.band.start)/d.hand.band.step),r=Math.ceil((b.y+b.h-d.hand.rows.start)/d.hand.rows.step);
    for(const p of [pos(c,r),pos(10000,10000)])assert.equal(E.lookup.at(d.r.index,p.x,p.y,24),-1);
  }
  const d=draw(sparse(20000,2),80,24),b=d.r.index.marks[0].shape;assert.equal(d.r.index.marks[E.lookup.at(d.r.index,b.x+b.w/2,b.y+b.h/2,24)].datum,20001);
  const f=variant('single',f=>f.columns={x:['a-name-that-is-much-too-long-to-fit-on-this-axis','b','a-name-that-is-much-too-long-to-fit-on-this-axis','b'],y:['r1','r1','r2','r2'],v:[1,2,3,4]});
  for(const frame of [[200,140],[480,320]]) {const lm=model(draw(f,...frame).r);assert.equal(lm.cuts.length,1);for(const datum of [1,3]){const cut=key(lm,datum,'ArrowRight');assert.equal(cut.kind,'cut');assert.equal(I.keyTarget(lm,cut,'ArrowLeft').target.datum,1);for(const k of ['ArrowRight','ArrowUp','ArrowDown'])same(I.keyTarget(lm,cut,k).target,cut);}}
  const lm=model(draw(f,160,160).r);assert.equal(lm.cuts.length,0);assert.equal(key(lm,3,'ArrowRight').datum,3);
});

// Each assertion is first observed green on the real engine, red on one independently mutated copy, then green again.
test("G2–G8 model geometry labels lookup and cap falsifications name the changed rule",()=>{
  const fx=n=>structuredClone(FX[n]);
  const agg=variant('single',f=>f.columns={x:['a','a','a','a'],y:['r','r','r','r'],v:[1,2,null,4]});
  const full=(r,f,w=960,h=540)=>r.render(inputOf(f,w,h));
  const capture=(r,f,w=960,h=540)=>{r.seen=null;const out=full(r,f,w,h);return {out,...r.seen};};
  const nearSlot=r=>{const d=capture(r,fx('grid-4x3'));const p=d.out.index.marks[0].shape;close(p.x,d.hand.band.start);close(p.w,d.hand.band.step);};
  const geometry=r=>{const d=capture(r,fx('narrow-cells'),80,24);const p=rects(d.out)[0];close(p.w,Math.max(0.01,r.scales.r2(d.hand.band.step-Math.min(d.h.theme.n('--chart-gap'),0.25*d.hand.band.step))),1e-9);};
  const cellFloor=r=>{const d=full(r,sparse(20000),80,24);const p=rects(d)[0];assert.ok(p.w>=0.01&&p.h>=0.01);};
  const boxFloor=r=>{const d=full(r,sparse(20000),80,24);const p=d.index.marks[0].shape;assert.ok(p.w>=0.01&&p.h>=0.01);};
  const keyCheck=r=>{const out=full(r,fx('grid-4x3'));const lm=I.modelOf(r,out,r.measure.measurer(null).fn);const get=(a,k)=>I.keyTarget(lm,target(a),k).target.datum;assert.equal(get(0,'ArrowRight'),1);assert.equal(get(1,'ArrowDown'),5);};
  const checkCount=r=>{const f=structuredClone(agg);f.document.config.encoding.color.aggregate='count';const d=capture(r,f);assert.equal(d.m.cells[0].agg.num,3);};
  const labels=r=>{const d=capture(r,fx('labels-values'),200,140);for(const t of d.out.primitives.filter(p=>p.k==='text'&&p.role==='guide')){const p=rects(d.out).find(p=>Math.abs(p.x+p.w/2-t.x)<0.011);assert.ok(d.h.width(t.text,t.size)<=p.w-2*r.tables.look('valueLabelGap')*t.size+1e-9);}};
  const plants=[
    ['G2 empty as zero','if (values.length === 0) return;','if (values.length === 0) values.push(0);',r=>assert.equal(full(r,fx('all-null')).state,'no-data')],
    ['G2 count rows','if (!D.isNull(V[i])) vals.push(V[i]);','vals.push(V[i]);',checkCount],
    ['G2 warning once','dropped = true;','dropped = true; warnings.push({code:"chart.rule.null-category"});',r=>{const f=variant('single',f=>f.columns={x:[null,null,'a'],y:['r','r','r'],v:[1,2,3]});assert.equal(full(r,f).summary.warnings.filter(w=>w.code==='chart.rule.null-category').length,1);}],
    ['G3 sequential only','R.colour.of(shade,','R.colour.of({scale:"sequential"},',r=>assert.ok(rects(full(r,fx('diverging'))).some(p=>p.fill.startsWith('--chart-div-')))],
    ['G3 mid omitted','R.colour.of(shade,','R.colour.of({scale:shade.scale},',r=>{const d=capture(r,fx('diverging-mid'));same([d.m.shade.scale.loText,d.m.shade.scale.hiText],['−10','34']);}],
    ['G3 series legend','series: [], legend: null','series: [{id:"wrong",title:"wrong",colour:"--chart-series-1"}], legend: null',r=>assert.equal(full(r,fx('grid-4x3')).summary.series.length,0)],
    ['G4 uncapped gap','look("cellGapMaxShare")','1',geometry],
    ['G4 wrong radius','theme.n("--chart-cell-radius")','theme.n("--chart-bar-radius")',r=>{const p=rects(full(r,fx('single')))[0];assert.equal(p.rx,2.5);}],
    ['G4 reversed y','var ci = new Map(), ri = new Map();','rows.reverse(); var ci = new Map(), ri = new Map();',r=>same(capture(r,fx('grid-4x3')).m.rows.map(c=>c.raw),['r01','r02','r03'])],
    ['G4 unstable datum','datum: r * cols.length + c','datum: cells.length',r=>{const d=capture(r,fx('missing-cells'));for(const c of d.m.cells)assert.equal(c.datum,c.r*d.m.cols.length+c.c);}],
    ['G4 rect floor','var raw = r2(length), kept = Math.max(STEP, raw), start = r2(at);','var raw = r2(length), kept = raw, start = r2(at);',cellFloor],
    ['G4 box floor','var bx = side(sx, hand.band.step, right), by = side(sy, hand.rows.step, bottom);','var bx = {at:r2(sx),size:r2(hand.band.step)}, by = {at:r2(sy),size:r2(hand.rows.step)};',boxFloor],
    ['G5 fixed ink','var ink = R.theme.onFill(theme.colour(fill), theme.colour("--chart-on-fill-light"), theme.colour("--chart-on-fill-dark"));','var ink = "--chart-on-fill-light";',r=>{const d=full(r,fx('labels-diverging'));assert.ok(d.primitives.some(p=>p.role==='guide'&&p.k==='text'&&p.fill==='--chart-on-fill-dark'));}],
    ['G5 no fit','h.width(text, small) <= dx.size - 2 * pad + 1e-9 && h.boxH(small) <= dy.size + 1e-9','true',labels],
    ['G5 wrong default','look("heatmapLabelsDefault")','"values"',r=>assert.equal(full(r,fx('grid-4x3')).primitives.filter(p=>p.role==='guide'&&p.k==='text').length,0)],
    ['G6 fill box','shape: { kind: "box", x: bx.at, y: by.at, w: bx.size, h: by.size }','shape: { kind: "box", x: dx.at, y: dy.at, w: dx.size, h: dy.size }',nearSlot],
    ['G6 row datum','row: c.r,','row: c.datum,',keyCheck],
    ['G6 row column','row: c.r,','row: c.c,',keyCheck],
    ['G6 row zero','row: c.r,','row: 0,',keyCheck],
    ['G6 no emphasis','emphasis: "ring"','emphasis: null',r=>{const out=full(r,fx('single')),lm=I.modelOf(r,out,r.measure.measurer(null).fn);assert.equal(I.ringPrims(r,lm,0,I.measures(r,'light')).length,1);}],
  ];
  const make=(from,to)=>engine(s=>{plant(s,FILE,from,to);plant(s,FILE,REGISTER,'R.type("heatmap", { channels: CHANNELS, model: model, marks: function(m,hand,h) { R.seen={m,hand,h}; return marks(m,hand,h); },');});
  for(const [name,from,to,verify] of plants) {
    const good=make('var STEP = 0.01;','var STEP = 0.01; ');verify(good);const bad=make(from,to);
    assert.throws(()=>verify(bad),undefined,name+' must turn red');verify(good);console.log(name+': GREEN / targeted RED / GREEN');
  }
  const cap=engine(s=>{s['06-tables.js']=s['06-tables.js'].replace('"heatmap.marks":','"removed.marks":');});
  const capCheck=r=>assert.equal(full(r,fx('thousand-one')).summary.refusal?.code,'chart.too_many_marks');capCheck(E);assert.throws(()=>capCheck(cap));capCheck(E);console.log('G7 cap: GREEN / targeted RED / GREEN');
});
