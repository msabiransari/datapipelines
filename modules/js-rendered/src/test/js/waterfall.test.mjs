// #1073: expectations from delivered rows and the frame, independent of the emitted marks.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, inputOf, chartFixtures, MODULE } from "./engine.mjs";
import { plant } from "./forms.mjs";
import { expectedWaterfallMarks, WIDTHS, HEIGHTS, violations, textBox } from "./geometry.mjs";
const KEY = "types/waterfall.js";
const E = engine(s => plant(s, KEY, 'model: model, marks: marks,', 'model: function (d,c,t) { var m = model(d,c,t); R.modelSeen = m; return m; }, marks: function (m,hand,h) { R.handSeen = hand; return marks(m,hand,h); },'));
const I = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === "waterfall").map(f => [f.name, f.fixture]));
const boxOf = p => {const b=textBox(p);return {x:b[0][0],y:b[0][1],w:b[1][0]-b[0][0],h:b[3][1]-b[0][1]};};
const plain = x => JSON.parse(JSON.stringify(x));
const same = (a,b) => assert.deepEqual(plain(a),plain(b));
function draw(f, w=960,h=540, extra={}) { E.modelSeen=null; E.handSeen=null; const r=E.render(inputOf(f,w,h,extra)); return {r,m:E.modelSeen,hand:E.handSeen}; }
function variant(name,fn) { const f=structuredClone(FX[name]);fn(f,f.document.config.encoding,f.document.config.mark);return f; }
const marks = r => r.primitives.filter(p=>p.role === "mark");
const labels = r => r.primitives.filter(p=>p.k === "text" && p.role === "guide");
const warning = r => r.summary.warnings.map(x=>x.code).filter(x=>x.startsWith("chart.rule."));
function rows(names,values,sort=null,type="STRING") { return variant("basic",(f,e)=>{f.columns={x:names,v:values};f.types={x:type,v:"DOUBLE"};e.x={field:"x",type:type === "DATE" ? "temporal":"nominal",sort};e.y={field:"v",type:"quantitative",aggregate:"sum",unit:"USD"};}); }

test("A.2: opening, ordered changes and total; nulls, zero, aggregation and warnings",()=>{
  const {m,r}=draw(FX.basic);
  same(m.bars.map(b=>[b.kind,b.from,b.to,b.num]),[["opening",0,100,100],["rise",100,140,40],["fall",140,125,-15],["fall",125,95,-30],["fall",95,83,-12],["rise",83,105,22],["total",0,105,105]]);
  assert.equal(r.summary.marks,7);
  for(const [name,count] of [["zero-steps",6],["repeat-category",4],["one-row",2],["no-total",4],["overflow",1]]) { const d=draw(FX[name]);assert.equal(d.r.summary.marks,count,name);assert.equal(expectedWaterfallMarks(FX[name]),count,name); }
  same(draw(FX["repeat-category"]).m.bars.map(b=>b.num),[17,5,-4,18]);
  same(draw(rows(["A","A","B","B","C"],[4,8,1,3,9])).m.bars.map(b=>b.num),[12,4,9,25]);
  const nulls=draw(rows([null,null,"A","B","C"],[1,2,null,0,4]));same(warning(nulls.r),["chart.rule.null-category"]);same(nulls.m.bars.map(b=>b.num),[0,4,4]);
  const nf=draw(rows(["A","B","C"],[1e308,1e308,1e308]));same(warning(nf.r),["chart.rule.non-finite"]);
  for(const [aggregate,want] of [["sum",12],["mean",6],["min",4],["max",8],["count",2]]) {const f=rows(["A","A"],[4,8]);f.document.config.encoding.y.aggregate=aggregate;assert.equal(draw(f).m.bars[0].num,want);}
});

test("A.2/G2/G8: eleven handwritten order and overflow cases, both model and oracle",()=>{
  const cases=[
    [["A","B","C"],[1e308,1e308,-1e308],null,["A"]],
    [["A","B","C"],[1e308,1e308,-1e308],"descending",["C","B","A","Total"]],
    [["A","B","C"],[1e308,1e308,-1e308],["C","B","A"],["C","B","A","Total"]],
    [["A","B","C"],[1e308,1e308,-1e308],"ascending",["A"]],
    [["A","B","C"],[1e308,1e308,-1e308],["C"],["C","A","B","Total"]],
    [["A","B","C"],[1e308,1e308,-1e308],["absent","B"],["B"]],
    [["C","A","B"],[-1e308,1e308,1e308],"ascending",["A"]],
    [["C","A","B"],[-1e308,1e308,1e308],null,["C","A","B","Total"]],
    [["A","\uff5e","😀"],[1,2,3],"ascending",["A","\uff5e","😀","Total"]],
    [["A","\uff5e","😀"],[1,2,3],"descending",["😀","\uff5e","A","Total"]],
    [["A","A","B","C"],[1e308,-1e308,1e308,1e308],"descending",["C"]],
  ];
  for(const [n,v,s,want] of cases){const f=rows(n,v,s);const d=draw(f);same(d.m.bars.map(b=>b.name),want);assert.equal(expectedWaterfallMarks(f),want.length);if(want.length===1)same(warning(d.r),["chart.rule.non-finite"]);}
  for(const [type,sort,n] of [["BIGDECIMAL","ascending",["1","2"]],["DATE","ascending",["2026-01-01T12:00:00Z","2026-01-02"]],["STRING","y",["A","B"]]])assert.throws(()=>expectedWaterfallMarks(rows(n,[1,2],sort,type)),/expectedWaterfallMarks/);
});

test("A.2: seeded order samples compare independent oracle with model, with changed-count floors",()=>{
  let seed=1073; const next=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed;};
  const changed={ascending:0,descending:0,list:0,STRING:0,INTEGER:0,DATE:0};
  for(let i=0;i<4000;i++) {const type=["STRING","INTEGER","DATE"][i%3];const ns=type === "STRING" ? ["A","B","C"]:type === "INTEGER" ? [1,2,3]:["2026-01-01","2026-01-02","2026-01-03"];const n=(next()>>>16)%2 ? ns.slice():ns.slice().reverse();const v=[1e308,1e308,-1e308];if(next()%2)v.reverse();const mode=["ascending","descending","list"][Math.floor(i/3)%3];const s=mode === "list" ? n.slice().reverse():mode;const f=rows(n,v,s,type);const d=draw(f);assert.equal(d.r.summary.marks,expectedWaterfallMarks(f));const input=rows(n,v,null,type);if(expectedWaterfallMarks(input)!==d.r.summary.marks){changed[mode]++;changed[type]++;}}
  for(const [k,n] of Object.entries(changed))assert.ok(n>=30,k+":"+n);console.log("waterfall ordered samples: 4000",changed);
});

test("A.2/A.3: exact running text and labels, including half-even decimal total",()=>{
  for(const [values,want] of [[["9007199254740993","1","1"],["9,007.2T USD","1 USD","1 USD","9,007.2T USD"]],[["9007199254740993","1000000000000001"],["9,007.2T USD","1,000T USD","10,007.2T USD"]],[["9007199254740993","-1000000000000001"],["9,007.2T USD","−1,000T USD","8,007.2T USD"]]]) {const f=rows(values.map((_,i)=>String(i)),values);f.types.v="BIGINTEGER";const d=draw(f,1600,600);same(d.m.bars.map(b=>b.label),want);same(d.m.layout.endLabels,want);same(labels(d.r).map(p=>p.text),want);}
  const d=draw(FX.decimal);assert.equal(d.m.bars.at(-1).label,"0.2");assert.equal(d.m.bars[1].run,"0.30");
});

test("A.2/G7: bars cap includes total; rows cap precedes model",()=>{
  for(const [n,total,state] of [[49,"end","rendered"],[50,"end","refused"],[51,"none","refused"]]){const f=rows(Array.from({length:n},(_,i)=>String(i)),Array(n).fill(1));f.document.config.mark.total=total;const d=draw(f);assert.equal(d.r.state,state);if(state === "refused")assert.equal(d.r.summary.refusal.code,"chart.too_many_marks");}
  const f=rows(Array(20001).fill("A"),Array(20001).fill(1));const d=draw(f);assert.equal(d.r.state,"refused");assert.equal(d.m,null);assert.equal(d.r.summary.refusal.code,"chart.too_many_rows");
});

test("A.3/A.6: every fixture across the sweep: bars, connectors, labels and bounds",()=>{
  let paths=0,drawn=0,missing=0,pictures=0,exact=0;
  for(const [name,f] of Object.entries(FX))for(const w of WIDTHS)for(const h of HEIGHTS){const {r,m,hand}=draw(f,w,h);pictures++;assert.equal(violations(r.primitives,w,h).violations.length,0,name+` ${w}x${h}`);if(r.state!=="rendered")continue;
    assert.equal(marks(r).length,expectedWaterfallMarks(f),name);assert.equal(r.summary.marks,marks(r).length);
    const ls=labels(r),bs=marks(r),cs=r.primitives.filter(p=>p.k === "line" && p.role === "guide" && p.stroke === "--chart-axis");
    assert.equal(cs.length,bs.length-1,name);const bw=hand.band.step*E.tables.look("barShare");
    for(let i=0;i<bs.length;i++){const b=bs[i],v=m.bars[i];assert.equal(b.fill,v.kind === "rise" ? "--chart-positive":v.kind === "fall" ? "--chart-negative":"--chart-total");if(b.k === "path"){paths++;for(let q=0;q<b.d.length;q++)if(b.d[q] === "A")assert.ok(b.d[q+1]<=Math.abs(hand.value.scale(Math.max(hand.value.lo,Math.min(hand.value.hi,v.from)))-hand.value.scale(Math.max(hand.value.lo,Math.min(hand.value.hi,v.to))))+0.02);}
      if(i<cs.length){const c=cs[i];assert.equal(c.y1,c.y2);assert.ok(Math.abs(c.x2-c.x1-(hand.band.step-bw))<0.03);assert.ok(Math.abs(c.y1-hand.value.scale(Math.max(hand.value.lo,Math.min(hand.value.hi,v.to))))<0.03);}}
    for(const p of ls){assert.ok(boxOf(p).w<=bw+6+0.03);assert.ok(boxOf(p).x>=hand.plot.x-0.03&&boxOf(p).y>=hand.plot.y-0.03&&boxOf(p).x+boxOf(p).w<=hand.plot.x+hand.plot.w+0.03&&boxOf(p).y+boxOf(p).h<=hand.plot.y+hand.plot.h+0.03);if(name === "decimal"){exact++;assert.ok(m.bars.some(b=>b.label === p.text));}}
    drawn+=ls.length;if(m.labelsOn&&r.level!=="minimal")missing+=m.bars.length-ls.length;
    if(!m.labelsOn||r.level === "minimal")assert.equal(ls.length,0);
  }
  assert.equal(pictures,3150);assert.ok(paths>1000&&drawn>1000&&missing>0&&exact>100);console.log({pictures,paths,drawn,missing,exact});
});

test("A.4/A.5: key-only legend, shape lookup, cards and keyboard plan at every size",()=>{
  for(const [w,h] of [[960,540],[300,180],[80,24],[160,32]]) {const {r,m}=draw(FX.basic,w,h);same(m.keys.map(k=>[k.title,k.colour]),[["Increase","--chart-positive"],["Decrease","--chart-negative"],["Opening and total","--chart-total"]]);assert.equal(r.summary.series.length,0);
    same(r,draw(FX.basic,w,h,{hidden:["rise","fall","total"]}).r);
    const entries=r.index.marks;assert.equal(entries.length,7);for(let i=0;i<entries.length;i++){const e=entries[i];assert.equal(e.row,0);assert.equal(e.emphasis,"ring");assert.equal(e.shape.kind,"box");assert.ok(e.shape.h>=7.99);}
    const model=I.modelOf(E,r,E.measure.measurer(null).fn);let target=I.keyTarget(model,null,"ArrowRight").target;assert.equal(target.datum,0);
    for(let i=0;i<7;i++){assert.equal(target.datum,i);for(const k of ["ArrowDown","ArrowUp"])assert.equal(I.keyTarget(model,target,k).target.datum,i);target=I.keyTarget(model,target,"ArrowRight").target;}
    assert.equal(target.datum,6);assert.equal(I.keyTarget(model,target,"ArrowRight").target.datum,6);assert.equal(I.keyTarget(model,target,"ArrowLeft").target.datum,5);assert.equal(I.keyTarget(model,target,"Home").target.datum,0);assert.equal(I.keyTarget(model,target,"End").target.datum,6);
    if(w === 80)console.log("waterfall 80x24 key cards:",entries.map(e=>e.card));
  }
  const d=draw(FX.basic);const e=d.r.index.marks;same(e[1].card.rows.map(x=>[x.name,x.value]),[["Increase","40 USD"],["Running total","140 USD"]]);same(e[2].card.rows.map(x=>[x.name,x.value]),[["Decrease","−15 USD"],["Running total","125 USD"]]);same(e[0].card.rows.map(x=>[x.name,x.value]),[["Opening","100 USD"]]);
});
