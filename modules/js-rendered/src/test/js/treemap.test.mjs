// Treemap contract (1078 A.2–A.5): expectations come from rows and the handed plot, never layout intermediates.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, MODULE } from "./engine.mjs";
import { plant } from "./forms.mjs";
import { expectedTreemapMarks, textBox, violations, WIDTHS, HEIGHTS } from "./geometry.mjs";

const KEY = "types/treemap.js", R = engine(), plain = x => JSON.parse(JSON.stringify(x));
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === "treemap").map(f => [f.name, f.fixture]));
const I = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const cells = r => r.primitives.filter(p => p.role === "mark" && p.k === "rect");
const same = (a, b, why) => assert.deepEqual(plain(a), plain(b), why);
const near = (a, b, tol = 0.025) => assert.ok(Math.abs(a - b) <= tol, `${a} versus ${b}`);
function input(values, names = values.map((_, i) => "L" + i), aggregate = "sum") {
  const f = structuredClone(FX.plain); delete f.document.config.title; delete f.document.config.encoding.size.unit;
  f.columns = { a: names, v: values }; f.document.config.encoding.size.aggregate = aggregate;
  return f;
}
const draw = (f, w = 480, h = 320, E = R, extra = {}) => E.render(inputOf(f, w, h, extra));
function handed(f, w, h) {
  let hand;
  const E = engine(s => plant(s, KEY, "model: model, marks: marks,", "model: model, marks: function(m, hand, h) { R.spy(hand); return marks(m, hand, h); },"));
  E.spy = x => { hand = x; }; const r = draw(f, w, h, E); return { r, hand };
}
const target = (r, i) => ({ kind: "mark", id: r.index.marks[i].series, datum: i });
const layer = r => I.modelOf(R, r, R.measure.measurer(null).fn);
function permutations(a) { return a.length ? a.flatMap((x, i) => permutations(a.filter((_, j) => i !== j)).map(p => [x, ...p])) : [[]]; }

test("D4 leaves: nulls and non-finite once, negative aggregates refuse, zeros have no cell", () => {
  const f = input([5, -2, 0, null, 3, 4], ["A", "A", "B", "C", null, null]);
  let r = draw(f); assert.equal(r.summary.marks, 1); assert.equal(r.index.marks[0].card.rows[0].value, "3");
  same(r.summary.warnings, [{ code: "chart.rule.null-category" }]);
  assert.equal(draw(input([5, -6], ["A", "A"])).summary.refusal.code, "chart.rule.negative-part");
  assert.equal(draw(input([0, null])).state, "no-data");
  const over = input([1.7e308, 1.7e308, 1.7e308, 1.7e308, 1], ["A", "A", "B", "B", "C"]);
  r = draw(over); assert.equal(r.summary.marks, 1); same(r.summary.warnings, [{ code: "chart.rule.non-finite" }]);
  const c = structuredClone(FX["color-series"]); c.columns.c[0] = null; c.columns.c[1] = null;
  same(draw(c).summary.warnings, [{ code: "chart.rule.null-category" }]);
  for (const [agg, value] of [["count", "3"], ["mean", "4"], ["min", "2"], ["max", "6"]]) {
    r = draw(input([2, null, 6], ["A", "A", "A"], agg)); assert.equal(r.index.marks[0].card.rows[0].value, value);
  }
  for (const names of [[2022, 2023], [true, false], [5, "5"], [0.30000000000000004, 1e21]]) {
    const q = draw(input([2, 1], names)); same(q.index.marks.map(m => m.card.rows[0].name), names.map(String));
  }
});

test("D4 caps precede layout: 8/9 series, 500/501 leaves and 501 with zero", () => {
  assert.equal(draw(FX["eight-groups"]).state, "rendered"); assert.equal(draw(FX["nine-groups"]).summary.refusal.code, "chart.too_many_series");
  const make = n => { const f = input(Array(n).fill(1)); f.columns.b = f.columns.a; f.columns.a = Array(n).fill("G"); f.document.config.encoding.path.push({field:"b"}); return f; };
  assert.equal(draw(make(500)).summary.marks, 500); assert.equal(draw(make(501)).summary.refusal.code, "chart.too_many_marks");
  const f = make(501); f.columns.v[0] = 0; assert.equal(draw(f).summary.marks, 500);
  const bad = input(Array(9).fill("not a number")); assert.equal(draw(bad).summary.refusal.code, "chart.too_many_series");
  const cap = R.tables.CAPS["treemap.marks"]; same([cap.kind, cap.basis, cap.limit], ["mark", "inherited", 500]);
});

test("D5 first appearance, all series sorts, split another colour value, shade and hidden ids", () => {
  const f = input([3, 2, 1], ["Z", "A", "B"]); same(draw(f).summary.series.map(s => s.title), ["Z", "A", "B"]);
  for (const [sort, want] of [[null,["Z","A","B"]], ["ascending",["A","B","Z"]], ["descending",["Z","B","A"]], [["B","Z"],["B","Z","A"]]]) {
    f.document.config.encoding.color = {field:"a",type:"nominal",sort}; same(draw(f).summary.series.map(s => s.title), want);
  }
  f.columns.c = ["p", "q", "p"]; f.columns.a = ["A","A","B"]; f.document.config.encoding.color = {field:"c",type:"nominal",sort:null};
  assert.equal(draw(f).summary.marks, 3); same(draw(f).index.marks.map(m => m.card.rows[0].name), ["A","A","B"]);
  for (const name of ["plain","color-series","shade"]) { const r = draw(FX[name]); same(draw(FX[name],480,320,R,{hidden:r.summary.series.map(s=>s.id)}),r); }
  for (let n = 1; n <= 8; n++) {
    const r = draw(input(Array(n).fill(1))); assert.equal(r.primitives.filter(p=>p.k==="group" && p.role==="legend").length,n===1?0:n);
    assert.equal(r.primitives.filter(p=>p.k==="group" && p.role==="legend" && p.legend!==null).length,0);
  }
  f.document.config.encoding.color.legend = null; assert.equal(draw(f).primitives.filter(p=>p.role==="legend").length,0);
});

test("D6 duplicate numeric inputs: all six orders keep sum, mean and shade geometry identical", () => {
  for (const agg of ["sum","mean"]) {
    let first;
    for (const vs of permutations([1e16,1,-1e16])) {
      const f = input([...vs,1],["A","A","A","B"],agg), r = draw(f);
      assert.equal(expectedTreemapMarks(f,[]),2); assert.equal(r.summary.marks,2);
      const got = r.index.marks.map(m=>[m.card.rows[0].name,m.card.rows[0].value,m.box]);
      if (first) same(got,first); else first=got;
    }
    let firstShade;
    for (const vs of permutations([1e16,1,-1e16])) {
      const f=input([1,1,1,1],["A","A","A","B"]); f.columns.s=[...vs,2];
      f.document.config.encoding.color={field:"s",type:"quantitative",aggregate:agg,scale:"sequential"};
      const r=draw(f), got=[cells(r).map(p=>p.fill),r.primitives.filter(p=>p.role==="legend")];
      if(firstShade) same(got,firstShade); else firstShade=got;
    }
  }
});

test("D6 deterministic subtree sums and name ties across 20 shuffles", () => {
  let seed=1078; const next=()=> (seed=(Math.imul(seed,1664525)+1013904223)>>>0);
  const f=input(Array.from({length:40},(_,i)=>[1e15,1,1e-9,3,7][i%5])); f.columns.b=f.columns.a; f.columns.a=f.columns.a.map((_,i)=>"G"+i%5); f.document.config.encoding.path.push({field:"b"});
  const signature=r=>r.index.marks.map(m=>[m.card.heading,m.card.rows[0].name,m.card.rows[0].value,m.box]);
  const want=signature(draw(f));
  for(let k=0;k<20;k++) { const order=Array.from({length:40},(_,i)=>i); for(let i=39;i>0;i--){const j=next()%(i+1);[order[i],order[j]]=[order[j],order[i]];} const q=structuredClone(f); for(const key of Object.keys(q.columns))q.columns[key]=order.map(i=>f.columns[key][i]); same(signature(draw(q)),want); }
  same(draw(input([1,1,2],["a b","a","B"])).index.marks.map(m=>m.card.rows[0].name),["B","a","a b"]);
});

test("D6/D7 cells reconstruct slots, conserve area, have the gap and rounded paint", () => {
  let checked=0, clamped=0;
  for(const name of ["plain","nested","deep","color-series","shade"]) for(const [w,h] of [[960,540],[480,320],[200,140],[80,24]]) {
    const {r,hand}=handed(FX[name],w,h), bs=cells(r), plot=hand.plot; let total=0, minimum=false;
    const vals=r.index.marks.map(m=>Number(m.card.rows[0].value.replaceAll(",","").replace(" GB",""))), vmax=Math.max(...vals), sum=vals.reduce((s,v)=>s+Math.max(v/vmax,1e-12),0);
    bs.forEach((b,i)=>{ checked++; assert.equal(b.rx,2.5); assert.ok(b.fill.startsWith("--chart-")); assert.equal(b.datum,i);
      for(const k of ["x","y","w","h"])near(b[k]*100,Math.round(b[k]*100),1e-7);
      if(b.w===1||b.h===1){clamped++;minimum=true;return;}
      const slot={x:b.x-1,y:b.y-1,w:b.w+2,h:b.h+2}; total+=slot.w*slot.h;
      assert.ok(slot.x>=plot.x-0.02&&slot.y>=plot.y-0.02&&slot.x+slot.w<=plot.x+plot.w+0.02&&slot.y+slot.h<=plot.y+plot.h+0.02);
      const expected=plot.w*plot.h*Math.max(vals[i]/vmax,1e-12)/sum; assert.ok(Math.abs(slot.w*slot.h/expected-1)<=0.005);
      for(const c of bs.slice(i+1).filter(c=>c.w>1&&c.h>1)) assert.ok(Math.min(b.x+b.w,c.x+c.w)-Math.max(b.x,c.x)<=0.02||Math.min(b.y+b.h,c.y+c.h)-Math.max(b.y,c.y)<=0.02);
    }); if(!minimum)near(total,plot.w*plot.h,12);
  }
  const {r,hand}=handed(input([1,1],["A","B"]),480,320),bs=cells(r); near(bs.reduce((s,b)=>s+b.w*b.h,0),130458.24); near(hand.plot.w*hand.plot.h,132518.88); near(bs[1].x-bs[0].x-bs[0].w,2);
  assert.ok(checked>300); console.log(`treemap slot sweep: cells=${checked}, clamped=${clamped}`);
});

test("D7 every positive leaf is at least one pixel at every frame", () => {
  const f=input(Array.from({length:100},(_,i)=>Math.pow(0.7,i))); f.columns.b=f.columns.a;f.columns.a=Array(100).fill("G");f.document.config.encoding.path.push({field:"b"});
  for(const [w,h] of [[960,540],[480,320],[240,160],[200,140],[160,100],[120,60],[80,24]]) {const r=draw(f,w,h);assert.equal(r.summary.marks,100);assert.equal(cells(r).length,100);for(const b of cells(r)){assert.ok(b.w>=1);assert.ok(b.h>=1);}}
});

test("D8 whole labels inside their cell's half-room over the sweep and each option", () => {
  let names=0,values=0,pictures=0;
  for(const name of ["plain","nested","deep","long-names","shade","unit-title"]) for(const option of [undefined,"none","names"]) for(const w of WIDTHS) for(const h of HEIGHTS) {
    const f=structuredClone(FX[name]); if(option)f.document.config.mark.labels=option;
    const r=draw(f,w,h);pictures++;same(violations(r.primitives,w,h).violations,[]);
    for(let i=0;i<r.primitives.length;i++){const p=r.primitives[i];if(p.k!=="group"||p.role!=="mark")continue;
      const kids=r.primitives.slice(i+1,i+1+p.n),box=kids.find(k=>k.k==="rect"),ts=kids.filter(k=>k.k==="text");
      if(option==="none"||r.level==="minimal")assert.equal(ts.length,0); if(option==="names")assert.ok(ts.length<=1);
      ts.forEach((t,j)=>{assert.notEqual(t.text,"");assert.ok(t.shown===null||t.shown===t.text);if(j===0)names++;else values++;
        const points=textBox(t);assert.ok(Math.min(...points.map(p=>p[0]))>=box.x+6-0.02);assert.ok(Math.max(...points.map(p=>p[0]))<=box.x+box.w-6+0.02);
        assert.ok(Math.min(...points.map(p=>p[1]))>=box.y+5-0.02);assert.ok(Math.max(...points.map(p=>p[1]))<=box.y+box.h-5+0.02);
      });
    }
  }
  assert.ok(names>15000);assert.ok(values>5000);console.log(`treemap labels: pictures=${pictures}, names=${names}, values=${values}, outside=0, cut=0, empty=0`);
  const f=input([1],[""]);assert.equal(draw(f).primitives.filter(p=>p.k==="text"&&p.role==="mark").length,0);
});

test("D8 ink follows on-fill contrast, grid ink, and changed text tokens alter fit and levels", () => {
  const luminance=rgb=>rgb.slice(0,3).map(v=>v/255).map(v=>v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4)).reduce((s,v,i)=>s+v*[0.2126,0.7152,0.0722][i],0);
  for(const theme of ["light","dark"])for(const name of ["plain","shade","shade-diverging"]){const r=draw(FX[name],960,540,R,{theme}),t=R.theme.resolve(theme);
    for(let i=0;i<r.primitives.length;i++){const p=r.primitives[i];if(p.k!=="group"||p.role!=="mark")continue;const kids=r.primitives.slice(i+1,i+1+p.n),b=kids[0];
      for(const text of kids.filter(p=>p.k==="text")){if(b.fill==="--chart-grid")assert.equal(text.fill,"--chart-ink");else{const a=luminance(t.colour(b.fill)),c=luminance(t.colour(text.fill));assert.ok((Math.max(a,c)+0.05)/(Math.min(a,c)+0.05)>=(b.fill.startsWith("--chart-series")?4.5:4.29));}}
    }
  }
  for(const [token,value]of[["--chart-text",14],["--chart-text-small",13],["--chart-title",16]]) {const base=draw(FX.plain,360,240),r=draw(FX.plain,360,240,R,{theme:{base:"light",tokens:{[token]:value}}});assert.equal(base.level,"full");assert.equal(r.level,"compact");}
});

test("D9/D10 lookup boxes match cells: centres, corners, gaps, dense last-containing and keys", () => {
  for(const [name,w,h]of[["deep",480,320],["many-leaves",120,60],["many-leaves",80,24]]) {
    const r=draw(FX[name],w,h),bs=cells(r),m=layer(r); assert.equal(r.index.marks.length,bs.length);
    for(const [i,b]of bs.entries()){const entry=r.index.marks[i];same(entry.box,{x:b.x,y:b.y,w:b.w,h:b.h});assert.equal(entry.row,0);assert.equal(entry.emphasis,"ring");
      for(const [x,y]of[[b.x+b.w/2,b.y+b.h/2],...(w>120?[[b.x+0.4,b.y+0.4],[b.x+b.w-0.4,b.y+b.h-0.4]]:[])]){
        const containing=r.index.marks.filter(e=>x>=e.box.x&&y>=e.box.y&&x<=e.box.x+e.box.w&&y<=e.box.y+e.box.h).at(-1);
        assert.equal(I.lookup(R,m,{x,y}).mark.datum,containing.datum);if(w!==80)assert.equal(containing.datum,i);
      }
    }
    let t=I.keyTarget(m,null,"ArrowRight").target;const seen=new Set();for(let i=0;i<bs.length;i++){seen.add(t.datum);t=I.keyTarget(m,t,"ArrowRight").target;}assert.equal(seen.size,bs.length);
  }
  const r=draw(input([1,1],["A","B"])),bs=cells(r);assert.equal(I.lookup(R,layer(r),{x:bs[0].x+bs[0].w+1,y:bs[0].y+5}).mark,null);
});

test("D10 card heading, unit once, true shares and underflow text; eight look fields stay live", () => {
  for(const name of ["plain","nested","deep"]) {const r=draw(FX[name]);for(const m of r.index.marks){assert.equal(m.card.keep,0);assert.equal(m.card.rows.length,2);assert.equal(m.card.rows[1].name,"Share of all");assert.equal(m.card.heading===null,name==="plain");}}
  const r=draw(FX.plain);same(r.index.marks.map(m=>m.card.rows.map(l=>l.value)),[["50 GB","50%"],["30 GB","30%"],["15 GB","15%"],["5 GB","5%"]]);
  const shares=r=>Object.fromEntries(r.index.marks.map(m=>[m.card.rows[0].name,m.card.rows[1].value]));
  same(shares(draw(FX["edge-values"])),{Huge:"100%",One:"1e−298%",Tiny:"<1%",Denormal:"<1%"});
  const nearUnderflow=draw(input([1.7e308,1],["Huge","One"]));assert.equal(nearUnderflow.index.marks[1].card.rows[1].value,"5.88e−307%");
  const E=engine(); E.tables.LOOK.treemapTinyText.value="Tiny share";same(shares(draw(FX["edge-values"],480,320,E)),{Huge:"100%",One:"1e−298%",Tiny:"Tiny share",Denormal:"Tiny share"});
});

test("D11 one keyboard row, ends and cut-title exit; D12 ring and dim", () => {
  const f=input(Array.from({length:30},(_,i)=>i+1));f.columns.b=f.columns.a;f.columns.a=f.columns.a.map((_,i)=>"G"+i%5);f.document.config.encoding.path.push({field:"b"});
  const r=draw(f),m=layer(r);let t=target(r,0);
  for(const [key,want]of[["ArrowRight",1],["ArrowRight",2],["ArrowRight",3],["ArrowLeft",2],["ArrowLeft",1],["ArrowLeft",0],["ArrowLeft",0],["ArrowDown",0],["ArrowUp",0],["End",29],["ArrowRight",29],["Home",0]]){t=I.keyTarget(m,t,key).target;assert.equal(t.datum,want);}
  const cut=structuredClone(f);cut.document.config.title={text:"A very long title ".repeat(100)};const c=draw(cut),cm=layer(c);assert.equal(I.keyTarget(cm,target(c,29),"ArrowRight").target.kind,"cut");
  for(const name of ["plain","shade"]){const r=draw(FX[name]),m=layer(r),state={last:"key",target:target(r,0),hit:null,pointer:null};
    const scene=I.scene(R,m,{frame:{w:480,h:320}},I.measures(R,"light"),R.measure.measurer(null).fn,state);
    const ring=scene.prims.find(p=>p.k==="rect"&&p.role==="guide");assert.ok(ring);assert.equal(ring.rx,4);assert.equal(ring.stroke,cells(r)[0].fill);
    const dim=cells(r).filter(p=>p.series!==scene.active).length;assert.equal(dim,name==="plain"?3:0);
  }
});
