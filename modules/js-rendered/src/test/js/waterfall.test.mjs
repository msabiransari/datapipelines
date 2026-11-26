// #1073: expectations from delivered rows and the frame, independent of the emitted marks.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, inputOf, chartFixtures, MODULE } from "./engine.mjs";
import { plant } from "./forms.mjs";
import { expectedWaterfallMarks, WIDTHS, HEIGHTS, violations, textBox, markBox } from "./geometry.mjs";
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


test("R2/G4: after the bars, Right reaches shortened text only when present",()=>{
  for(const [name,w,h,hasCuts] of [["basic",960,540,false],["long-labels",960,540,true],["hostile",480,300,true]]) {
    const {r}=draw(FX[name],w,h), model=I.modelOf(E,r,E.measure.measurer(null).fn);
    assert.equal(model.cuts.length>0,hasCuts,name);
    let target=I.keyTarget(model,null,"ArrowRight").target;
    for(let datum=0;datum<r.index.marks.length;datum++) {
      same(target,{kind:"mark",id:null,datum});
      for(const key of ["ArrowUp","ArrowDown"])same(I.keyTarget(model,target,key).target,target);
      if(datum<r.index.marks.length-1)target=I.keyTarget(model,target,"ArrowRight").target;
    }
    const last=target;
    same(I.keyTarget(model,last,"Home").target,{kind:"mark",id:null,datum:0});
    same(I.keyTarget(model,last,"End").target,last);
    same(I.keyTarget(model,last,"ArrowLeft").target,{kind:"mark",id:null,datum:last.datum-1});
    target=I.keyTarget(model,last,"ArrowRight").target;
    same(target,hasCuts?{kind:"cut",n:0}:last);
    if(hasCuts) {
      same(I.keyTarget(model,target,"ArrowLeft").target,last);
      for(let n=0;n<model.cuts.length;n++) {
        same(target,{kind:"cut",n});
        assert.ok(model.cuts[n].text.length>0);
        target=I.keyTarget(model,target,"ArrowRight").target;
      }
      same(target,{kind:"cut",n:model.cuts.length-1});
    }
    console.log("R2 keyboard",name,w,h,"cuts",model.cuts.length,"last",last);
  }
});


test("A.5/G5: centres, top edges, gaps and thin or zero bars use the grown hit box",()=>{
  let thin=0,hits=0;
  for(const name of ["basic","wide-range","zero-steps"])for(const w of [80,160,480,960]) {
    const {r,m,hand}=draw(FX[name],w,300), es=r.index.marks;
    for(let i=0;i<es.length;i++) {
      const b=es[i].shape,x=b.x+b.w/2,y=b.y+b.h/2,p=r.index.plot;
      assert.equal(E.lookup.at(r.index,x,y,0),i);hits++;
      const height=Math.abs(hand.value.scale(m.bars[i].from)-hand.value.scale(m.bars[i].to));
      if(height<6&&y-3>=p.y&&y-3<=p.y+p.h) {thin++;assert.equal(E.lookup.at(r.index,x,y-3,0),i,"thin bar "+name);}
      if(b.y>=p.y&&b.y<=p.y+p.h)assert.equal(E.lookup.at(r.index,x,b.y,0),i);
      if(i+1<es.length)assert.equal(E.lookup.at(r.index,(b.x+b.w+es[i+1].shape.x)/2,y,0),-1);
    }
  }
  assert.ok(thin>10&&hits>40);console.log("waterfall lookup",{thin,hits});
  const {r}=draw(FX.basic),b=r.index.marks[1].shape;
  assert.equal(E.lookup.at(r.index,b.x+b.w/2,(r.index.plot.y+b.y)/2,0),-1);
});

test("A.3: authored domains clamp the bars and labels never overlap",()=>{
  let checked=0;
  for(const domain of [[0,120],[-20,120],[0,60]])for(const w of WIDTHS)for(const h of HEIGHTS) {
    const f=variant("basic",(f,e)=>{e.y.scale={domain};}),{r,hand}=draw(f,w,h);
    for(const b of marks(r)) {const box=markBox(b);assert.ok(box);assert.ok(box[1]>=hand.plot.y-0.02&&box[3]<=hand.plot.y+hand.plot.h+0.02);checked++;}
    assert.equal(violations(r.primitives,w,h).violations.length,0);
  }
  assert.ok(checked>1000);console.log("waterfall clamped bars",checked);
});

test("A.5: fitted cards keep the direction row and a flat bar has a ring",()=>{
  const width=E.measure.measurer(null).fn, measures=I.measures(E,"light",width);
  for(const [w,h] of [[80,24],[160,32],[960,540]]) {
    const {r}=draw(FX.basic,w,h),model=I.modelOf(E,r,width);
    const texts=[];
    for(let i=0;i<r.index.marks.length;i++) {
      const lines=I.markLines(model,i), card=I.markCard(E,width,lines,null,{w,h},measures);
      assert.ok(card);assert.ok(card.lines.some(l=>l.line.kept));
      if(h===24) {assert.equal(card.lines.length,1);assert.equal(card.lines[0].line.value,["100 USD","40 USD","−15 USD","−30 USD","−12 USD","22 USD","105 USD"][i]);}
      texts.push(card.lines.map(l=>l.line.kind==="heading"?l.text:l.line.kind==="more"?l.text:[l.label,l.line.value].filter(Boolean).join(" ")));
    }
    console.log("waterfall fitted key cards",w,h,JSON.stringify(texts));
  }
  const {r}=draw(FX["zero-steps"]),model=I.modelOf(E,r,width);
  const n=r.index.marks.findIndex(e=>e.shape.h===8);
  assert.ok(n>=0);const ring=I.ringPrims(E,model,n,measures);
  assert.equal(ring.length,1);assert.equal(ring[0].h,14);
});

// Every plant changes a fresh source copy in a VM. The product files and the pristine oracle stay untouched.
test("G2-G7: each renderer guard is observed GREEN, targeted RED, GREEN",()=>{
  const make=(from,to)=>engine(s=>{
    plant(s,KEY,from,to);
    plant(s,KEY,'model: model, marks: marks,','model: function(d,c,t){var m=model(d,c,t);R.seen=m;return m;}, marks: function(m,hand,h){R.seenHand=hand;return marks(m,hand,h);},');
  });
  const render=(R,f=FX.basic,w=960,h=540)=>R.render(inputOf(f,w,h));
  const nums=(R,f)=>{render(R,f);return plain(R.seen.bars.map(b=>b.num));};
  const picture=R=>{
    let n=0;
    for(const name of ["basic","decimal","fifty","zero-steps","wide-range"])for(const w of WIDTHS)for(const h of HEIGHTS) {
      const r=render(R,FX[name],w,h);if(r.state!=="rendered")continue;
      assert.equal(violations(r.primitives,w,h).violations.length,0,"picture bounds/overlap");
      assert.equal(marks(r).length,expectedWaterfallMarks(FX[name]),"bar count");
      const bs=marks(r),bw=r.index.marks[0].shape.w;
      for(const p of labels(r)){const b=boxOf(p),plot=r.index.plot;assert.ok(b.w<=bw+6.03,"label width");assert.ok(b.x>=plot.x-0.02&&b.y>=plot.y-0.02&&b.x+b.w<=plot.x+plot.w+0.02&&b.y+b.h<=plot.y+plot.h+0.02,"label outside plot");}
      for(let i=0;i<bs.length;i++){const b=markBox(bs[i]),level=R.seen.bars[i];
        // The arc must stay in the vertical extent already represented by the lookup box.
        const scale=R.seenHand.value.scale,p0=scale(level.from),p1=scale(level.to);assert.ok(b[1]>=Math.min(p0,p1)-0.03&&b[3]<=Math.max(p0,p1)+0.03,"arc outside bar");
      } n++;
    } assert.ok(n>500);
  };
  const fnull=rows([null,null,"A","B"],[1,2,null,4]);
  const plants=[
    ["G2 null value",'if (a.num === null) continue;','if (a.num === null) a.num = 0;',R=>same(nums(R,fnull),[4,4])],
    ["G2 repeated warning",'if (seen[code] !== true)','if (true)',R=>same(warning(render(R,fnull)),["chart.rule.null-category"])],
    ["G2 zero step",'if (a.num === null) continue;','if (a.num === null || a.num === 0) continue;',R=>assert.equal(render(R,FX["zero-steps"]).summary.marks,6)],
    ["G2 repeated category",'var key = D.keyOf(X[i]),','var key = String(i),',R=>same(nums(R,FX["repeat-category"]),[17,5,-4,18])],
    ["G2 order",'band.sort, function ()','null, function ()',R=>assert.equal(render(R,rows(["A","B","C"],[1e308,1e308,-1e308],"descending")).summary.marks,4)],
    ["G3 overflow",'if (!finite(to))','if (false)',R=>assert.equal(render(R,FX.overflow).summary.marks,1)],
    ["G3 exact sum",'running = allExact ? D.aggregate(parts, "sum", value.field || "count").exact : null;','running = String(to);',R=>{render(R,FX.decimal);assert.equal(R.seen.bars.at(-1).exact,"0.25");}],
    ["G3 start",'from: level, to: to','from: 0, to: to',R=>{render(R);assert.equal(R.seen.bars[1].from,100);}],
    ["G3 total cap",'var count = steps.length + (total === "end" && steps.length > 0 ? 1 : 0);','var count = steps.length;',R=>assert.equal(render(R,rows(Array.from({length:50},(_,i)=>String(i)),Array(50).fill(1))).state,"refused")],
    ["G4 width",'if (width > bw + look("waterfallLabelSlack") + 1e-9)','if (false)',picture],
    ["G4 overlap",'if (box[0] < p[2] && p[0] < box[2] && box[1] < p[3] && p[1] < box[3])','if (false)',picture],
    ["G4 plot",'if (box[0] < plot.x || box[1] < plot.y || box[2] > plot.x + plot.w || box[3] > plot.y + plot.h)','if (false)',picture],
    ["G4 radius",'bw * look("radiusMaxShare"), length','bw * look("radiusMaxShare")',picture],
    ["G4 exact labels",'T.value(b.exact !== null ? b.exact : b.num, look("stackTotalFormat"))','T.value(b.num, look("stackTotalFormat"))',R=>{render(R,FX.decimal);assert.equal(R.seen.bars.at(-1).label,"0.2");}],
    ["G4 connector",'h.rule("guide", "--chart-axis", x + bw','h.rule("mark", "--chart-axis", x + bw',R=>assert.equal(marks(render(R)).length,7)],
    ["G5 hit box",'Math.max(length, look("waterfallHitMin"))','length',R=>assert.ok(render(R,FX["zero-steps"]).index.marks.every(e=>e.shape.h>=7.99))],
    ["G5 emphasis",'emphasis: "ring"','emphasis: null',R=>assert.ok(render(R).index.marks.every(e=>e.emphasis==="ring"))],
    ["G6 row",'row: 0, key: true','row: i, key: true',R=>{const r=render(R),m=I.modelOf(R,r,R.measure.measurer(null).fn);assert.equal(I.keyTarget(m,{kind:"mark",id:null,datum:0},"ArrowRight").target.datum,1);}],
    ["G6 card order",'return { heading: b.name, rows: rows, keep: 0 };','return { heading: b.name, rows: rows.reverse(), keep: 0 };',R=>assert.equal(render(R).index.marks[1].card.rows[0].name,"Increase")],
    ["G7 word field",'title: look("waterfallRiseText")','title: "Increase"',R=>{R.tables.LOOK.waterfallRiseText.value="Up";render(R);assert.equal(R.seen.keys[0].title,"Up");}],
    ["G7 interaction field",'var step = hand.band.step,','var step = hand.band.step + look("markMove"),',R=>{const prior=R.tables.LOOK.markMove.value,before=render(R);R.tables.LOOK.markMove.value=prior*2;const after=render(R);R.tables.LOOK.markMove.value=prior;same(after,before);}],
  ];
  for(const [name,from,to,verify] of plants) {
    const good=make(from,from+" ");verify(good);
    assert.throws(()=>verify(make(from,to)),undefined,name+" must turn red");verify(good);
    console.log(name+": GREEN / targeted RED / GREEN");
  }
});

test("A.2/A.4: non-finite aggregates warn once, optional total and legend placement",()=>{
  const f=rows(["A","A","B","B","C"],[1e308,1e308,1e308,1e308,3]),d=draw(f);
  same(d.m.bars.map(b=>b.num),[3,3]);same(warning(d.r),["chart.rule.non-finite"]);
  const one=variant("one-row",(f,e,mark)=>{mark.total="none";});assert.equal(draw(one).r.summary.marks,1);
  const over=rows(Array.from({length:50},(_,i)=>String(i)),Array(50).fill(1));
  same(draw(over).r.summary.refusal.details,{marks:51,limit:50});
  for(const [w,h,level,want] of [[960,540,"full",["Increase","Decrease","Opening and total"]],[300,180,"compact",["Increase","Decrease","Opening and total"]],[240,180,"compact",["Increase","Decrease","+1"]],[80,24,"minimal",[]]]) {
    const {r}=draw(FX.basic,w,h),ls=r.primitives.filter(p=>p.role==="legend"&&p.k==="text");
    assert.equal(r.level,level);same(ls.map(p=>p.text),want);
    for(const l of ls)assert.ok(level==="full"?l.y<r.index.plot.y:l.y>r.index.plot.y+r.index.plot.h);
  }
});

test("A.3: both themes resolve direction colours, round the moving end and give strong labels to opening and total",()=>{
  for(const theme of ["light","dark"])for(const name of ["basic","all-negative","negative-cross"]) {
    const {r,m,hand}=draw(FX[name],960,540,{theme}),t=E.theme.resolve(theme);
    for(const p of marks(r)) {
      const b=m.bars[p.datum],token=b.kind==="rise"?"--chart-positive":b.kind==="fall"?"--chart-negative":"--chart-total";
      assert.equal(p.color,t.css(token));
      if(p.k==="path") {
        const box=markBox(p),up=b.to>=b.from;
        assert.ok(Math.abs(p.d[2]-(up?box[3]:box[1]))<0.02,"starts at the square end");
        const arc=p.d.indexOf("A"),r=p.d[arc+1];
        assert.ok(Math.abs(p.d[arc+3]-(up?box[1]:box[3]))<0.02,"arc at moving end");
        assert.ok(r<=box[3]-box[1]+0.02);
      }
    }
    for(const p of labels(r)) {
      const i=Math.floor((p.x-hand.band.start)/hand.band.step),b=m.bars[i],strong=b.kind==="opening"||b.kind==="total";
      assert.equal(p.fill,strong?"--chart-ink":"--chart-ink-2");
      assert.equal(p.weight,strong?t.n("--chart-title-weight"):400);
      if(b.num<0)assert.ok(p.text.startsWith("−"));else assert.ok(!p.text.startsWith("+"));
    }
  }
});
