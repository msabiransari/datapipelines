// #1076: arithmetic from rows and the handed plot; the engine is never its own oracle.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, MODULE } from "./engine.mjs";
import { plant } from "./forms.mjs";
import { expectedSparklineMarks, violations } from "./geometry.mjs";
const KEY = "types/sparkline.js";
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === "sparkline").map(f => [f.name, f.fixture]));
const clone = x => JSON.parse(JSON.stringify(x));
const eq = (a,b) => assert.deepEqual(clone(a),b);
const near = (a,b) => assert.ok(Math.abs(a-b) <= .02, `${a} against ${b}`);
function makeEngine(edit) {
  return engine(s => {
    plant(s, KEY, 'function marks(m, hand, h) {', 'function marks(m, hand, h) { if (R.spy) R.spy(m, hand);');
    if (edit) edit(s);
  });
}
const E = makeEngine();
function draw(fx=FX.rising,w=160,h=32,extra={},R=E) {
  let m, hand; R.spy=(a,b)=>{m=a;hand=b;};
  try { return {r:R.render(inputOf(fx,w,h,extra)),m,hand}; } finally { R.spy=null; }
}
function fixture(values, xs=values.map((_,i)=>i), y={}, mark={}) {
  const f=clone(FX["quantitative-x"]);f.columns={x:xs,v:values};f.document.config.encoding.x={field:"x",type:"quantitative"};
  Object.assign(f.document.config.encoding.y,y);Object.assign(f.document.config.mark,mark);return f;
}
const dots = r=>r.primitives.filter(p=>p.k==="circle"&&p.role==="mark");
const paths = r=>r.primitives.filter(p=>p.k==="path"&&p.role==="mark");
const labels = r=>r.primitives.filter(p=>p.k==="text"&&p.role==="guide");
const r2 = n=>Math.round(n*100)/100;
const theme=(point=4,gap=2,text=12)=>({theme:{base:"light",tokens:{"--chart-point":point,"--chart-gap":gap,"--chart-text":text}}});
function clean(r,w,h) { eq(violations(r.primitives,w,h).violations,[]); }

test("A.2 rows, warnings, gaps, aggregates and domains",()=>{
  for(const [f,count,warnings] of [[fixture([1,null,3]),2,[]],[fixture([1,Infinity,Infinity,3]),2,["chart.rule.non-finite"]],[fixture([0,-1,10,100],undefined,{scale:{type:"log"}}),2,["chart.rule.log-nonpositive"]],[fixture([1,2,3],[0,0,1]),2,[]],[fixture([null,null]),0,[]],[fixture([]),0,[]]]) {
    const {r}=draw(f);assert.equal(r.summary.marks,count);eq(r.summary.warnings.map(w=>w.code),warnings);
  }
  for(const name of ["rising","negative-unit","nominal-x","quantitative-x","null-gap","domain"]) for(const [w,h] of [[80,24],[160,32],[400,200]]) {
    const {m,hand}=draw(FX[name],w,h);if(name!=="domain")eq(m.layout.domain,[Math.min(...m.cells[0].filter(Boolean).map(c=>c.num)),Math.max(...m.cells[0].filter(Boolean).map(c=>c.num))]);else eq(m.layout.domain,[0,100]);
    assert.ok(hand.plot.w>0);
  }
  assert.equal(draw(FX.flat).m.layout.domain,null);assert.equal(draw(FX["one-point"]).m.layout.domain,null);
  assert.equal(draw(FX["log-zero"]).m.layout.log,true);
  for(const last of ["none","dot","value"]) {const d=draw(fixture([1,2],undefined,{}, {last}));assert.equal(d.m.layout.last,last==="value"?"2":null);assert.equal(d.m.layout.reach({theme:E.theme.resolve("light")}),last==="none"?1:4.5);}
});
function checkSpan(R=E) {
  for(const name of ["rising","nominal-x","quantitative-x","null-gap"]) {
    const f=clone(FX[name]); f.columns.v[0]=null;f.columns.v[f.columns.v.length-1]=null;
    const {r,hand}=draw(f,160,32,{},R), at=r.index.series[0].at;
    near(at[0],hand.plot.x);near(at.at(-1),hand.plot.x+hand.plot.w);
  }
}
test("A.3 first and last drawn points span the plot, even with end nulls",()=>checkSpan());
function checkFlat(R=E) {
  let pictures=0;
  for(const a of [0,5,-5,1e-300,1.1e308,1.7e308,-1.7e308,Number.MAX_VALUE,-Number.MAX_VALUE,Number.MIN_VALUE,-Number.MIN_VALUE])
    for(const values of [[a],[a,a,a,a,a,a],[a,a,null,a,a,a]]) for(const [w,h] of [[160,32],[80,24],[400,200]]) {
      const {r,hand}=draw(fixture(values),w,h,{},R);near(dots(r)[0].cy,hand.plot.y+hand.plot.h/2);
      for(const p of paths(r).filter(p=>p.stroke))for(let i=0,axis=0;i<p.d.length;i++){if(typeof p.d[i]==="string")axis=0;else {if(axis%2===1)near(p.d[i],hand.plot.y+hand.plot.h/2);axis++;}}
      clean(r,w,h);pictures++;
    }
  assert.equal(pictures,99);
}
test("A.3 every finite flat value sits at the middle",()=>checkFlat());
test("A.3 flat authored, zero and log scales keep their places",()=>{
  for(const [a,y,fraction] of [[50,{scale:{domain:[0,100]}},.5],[100,{scale:{domain:[0,100]}},0],[0,{scale:{domain:[0,100]}},1],[25,{scale:{domain:[0,100]}},.75],[5,{scale:{zero:true}},0],[-5,{scale:{zero:true}},1],[0,{scale:{zero:true}},.5],[50,{scale:{type:"log"}},.3]]){
    const {r,hand}=draw(fixture([a,a,a],undefined,y));near(dots(r)[0].cy,hand.plot.y+hand.plot.h*fraction);
  }
});
function extremeSpan(R=E) {
  let seed=1076,beyond=0;
  for(let k=0;k<400;k++) {
    const ints=[-10,10];for(let i=0;i<5;i++){seed=(1664525*seed+1013904223)>>>0;ints.push(seed%21-10);}
    const sorted=[...new Set(ints)].sort((a,b)=>a-b);const xs=sorted.map(x=>x*1e307);if(!Number.isFinite(xs.at(-1)-xs[0]))beyond++;
    const {r,hand}=draw(fixture(sorted.map((_,i)=>i+1),xs),160,32,{},R);
    r.index.series[0].at.forEach((x,i)=>near(x,hand.plot.x+hand.plot.w*(sorted[i]-sorted[0])/(sorted.at(-1)-sorted[0])));clean(r,160,32);
  }
  assert.ok(beyond>=100);console.log(`A.3 extreme span: 400 lists, ${beyond} spans beyond float64`);
}
test("A.3 quantitative spans beyond float64 use finite fractions",()=>extremeSpan());
function fillCheck(R=E) {
  const {r,hand}=draw(FX.domain,160,32,{},R);const p=paths(r).find(p=>p.fill);assert.ok(p);eq(p.fade,[.38,.02,hand.plot.y,hand.plot.y+hand.plot.h]);
}
test("A.3 fill, line, dot order, plot fade and flat opacity",()=>{
  fillCheck();
  for(const last of ["none","dot","value"])for(const fill of [true,false]){
    const {r}=draw(fixture([3,9,4],undefined,{}, {last,fill}));eq(paths(r).map(p=>p.fill?"fill":"line"),fill?["fill","line"]:["line"]);assert.equal(dots(r).length,last==="none"?0:1);
    assert.equal(labels(r).length,last==="value"?1:0);
  }
  const R=makeEngine();R.tables.LOOK.areaFade.value=false;const p=paths(draw(FX.rising,160,32,{},R).r).find(p=>p.fill);assert.equal(p.fade,null);assert.equal(p.opacity,.38);
  const one=draw(FX["one-point"]);assert.equal(paths(one.r).length,0);near(dots(one.r)[0].cx,one.hand.plot.x+one.hand.plot.w);
});
function dotSweep(R=E) {
  let pictures=0,smaller=0,whole=0;
  for(const point of [1,4,6,6.5,11,16])for(const gap of [0,2,8])for(const text of [8,12,32])for(const [w,h] of [[80,24],[160,24],[160,32],[100,31],[400,200]])for(const last of ["dot","value"])for(const f0 of [FX.rising,FX["one-point"]]) {
    const f=clone(f0);f.document.config.mark.last=last;const {r}=draw(f,w,h,theme(point,gap,text),R);assert.equal(r.state,"rendered");assert.equal(dots(r).length,1);
    const dot=dots(r)[0],asked=point-.5+gap/2,share=Math.min(asked,Math.min(w,h)/4)/asked;
    near(dot.r,r2((point-.5)*share));near(dot.strokeWidth,r2(gap*share));
    for(const edge of [dot.cx,dot.cy,w-dot.cx,h-dot.cy])assert.ok(edge+.02>=dot.r+dot.strokeWidth/2);
    for(const t of labels(r))assert.ok(t.x+.02>=dot.cx+dot.r+dot.strokeWidth/2);
    f.document.config.mark.last="none";assert.equal(dots(draw(f,w,h,theme(point,gap,text),R).r).length,0);
    clean(r,w,h);pictures++;if(share<1)smaller++;else whole++;
  }
  console.log(`A.4 kept reach: ${pictures} pictures, ${whole} whole, ${smaller} smaller`);assert.ok(smaller>400);assert.ok(whole>600);
}
test("A.4 dot and ring shrink together and every requested dot remains visible",()=>dotSweep());
function fitCheck(R=E) {
  const f=fixture([1.7e6,1.7e6]);
  for(const text of [12,20])for(const [w,h] of [[80,24],[160,32],[240,27],[240,28],[400,200]]) {
    const {r,m,hand}=draw(f,w,h,theme(4,2,text),R),width=R.measure.measurer(null).fn(m.layout.last,text,600);
    const fits=h>=1.4*text&&w-2*Math.max(text*.5,4.5)-Math.max(text*.5,4.5)-width>=w/2;
    assert.equal(labels(r).length,fits?1:0);clean(r,w,h);assert.ok(hand);
  }
  const text=12,width=R.measure.measurer(null).fn("1.7M",text,600),border=2*(12+6+width);
  for(const delta of [-1,-.01,-.001,0,.001,.01])assert.equal(labels(draw(f,border+delta,32,{},R).r).length,delta>=0?1:0);
}
test("A.4 independent width and height fit arithmetic, including equality",()=>fitCheck());
test("A.2/A.3 caps thin past 2000, preserve the end and refuse past 10000 rows",()=>{
  for(const n of [2000,2001,5000,10000,10001]){
    const f=fixture(Array.from({length:n},(_,i)=>50+Math.sin(i/7)*40));const {r,hand}=draw(f,400,200);
    if(n>10000){assert.equal(r.state,"refused");assert.equal(r.summary.refusal.code,"chart.too_many_rows");continue;}
    assert.equal(r.summary.marks,n);assert.equal(r.index.series[0].at.length,n);near(dots(r)[0].cx,hand.plot.x+hand.plot.w);
    const path=paths(r).find(p=>p.stroke),points=path.d.filter(x=>x==="M"||x==="C"||x==="L").length;
    if(n===2000)assert.equal(points,n);else assert.ok(points<=4*(Math.ceil(hand.plot.w)+1));
  }
});
const I=createRequire(import.meta.url)(join(MODULE,"../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
function scene(f,w,h,datum,mode="key",R=E) {
  const {r,hand,m}=draw(f,w,h,{},R),measure=R.measure.measurer(null).fn,model=I.modelOf(R,r,measure);model.theme=R.theme.resolve("light");assert.equal(model.crosshair,true,"form 1 crosshair");
  const target={kind:"mark",id:"measure",datum},pointer={x:r.index.series[0].at[r.index.series[0].datum.indexOf(datum)],y:hand.plot.y+hand.plot.h/2};
  const hit=I.lookup(R,model,pointer);const state=mode==="key"?{last:"key",target,hit:null,pointer:null}:{last:"pointer",target:null,hit,pointer};
  return {sc:I.scene(R,model,{frame:{w,h}},I.measures(R,"light"),measure,state),model,r,hand,m};
}
function cardCheck(R=E) {
  for(const [h,want] of [[24,["v","30"]],[39,["v","30"]],[55,["2024-05-01","v","30","+3 more"]],[70,["2024-05-01","v","30","+3 more"]],[86,["2024-05-01","v","30","Last","40","Highest","40","Lowest","12"]],[320,["2024-05-01","v","30","Last","40","Highest","40","Lowest","12"]]])for(const mode of ["key","pointer"]) {
    const {sc,model,hand}=scene(FX.rising,480,h,4,mode,R);assert.equal(model.crosshair,true);eq(sc.prims.filter(p=>p.k==="text").map(p=>p.text),want);
    const dot=sc.prims.find(p=>p.k==="circle"&&p.role==="guide");assert.ok(dot);near(dot.cy,hand.value.scale(30));
  }
}
test("A.5 LA17 selected interior point is kept at every card budget with both inputs",()=>cardCheck());
test("A.5 x unit once, no drawing change; ordinal keys after a null; T14 smallest-frame plan",()=>{
  const f=fixture([12,30,40],[1,2,3]);f.document.config.encoding.x.unit="km";
  for(const mode of ["key","pointer"]){const d=scene(f,480,320,1,mode);assert.equal(d.sc.prims.filter(p=>p.k==="text")[0].text,"2 km");assert.equal(d.sc.prims.filter(p=>p.k==="text").map(p=>p.text).join(" ").match(/km/g).length,1);}
  const bare=clone(f);delete bare.document.config.encoding.x.unit;eq(draw(f).r.primitives,clone(draw(bare).r.primitives));
  const d=scene(FX.rising,80,24,0);let t=null;const values=[12,18,15,22,30,28,35,31,26,24,29,40],plan=[];
  for(let i=0;i<12;i++){t=I.keyTarget(d.model,t,"ArrowRight").target;assert.equal(t.datum,i);eq(scene(FX.rising,80,24,i).sc.prims.filter(p=>p.k==="text").map(p=>p.text),["v",String(values[i])]);plan.push(`Right -> ${i}: v | ${values[i]}`);}
  for(const key of ["ArrowRight","ArrowDown","ArrowUp"])assert.equal(I.keyTarget(d.model,t,key).target.datum,11);
  console.log("T14 plan: "+plan.join("; "));
  const gap=scene(fixture([5,7,null,9,11]),400,200,0);t=null;for(let i=0;i<3;i++)t=I.keyTarget(gap.model,t,"ArrowRight").target;assert.equal(t.datum,3);
  assert.equal(I.lookup(E,d.model,{x:0,y:0}).mark,null);
});
test("A.6 oracle agrees with eleven hand-counted aggregate cases",()=>{
  const cases=[[[1,2,3],[0,0,1],"sum",{},2],[[1,null,3],[0,0,1],"mean",{},2],[[null,null],[0,0],"count",{},1],[[0,0],[0,0],"count",{scale:{type:"log"}},1],[[1e308,1e308],[0,0],"min",{},1],[[1e308,1e308],[0,0],"max",{},1],[[-1,2],[0,0],"min",{scale:{type:"log"}},0],[[1e308,1e308],[0,0],"sum",{},0],[[null,null],[0,0],"sum",{},0],[[1,2],[0,0],"count",{},1],[[1,2,3],[0,1,2],"count",{},3]];
  for(const [v,x,aggregate,y,n] of cases){const f=fixture(v,x,{...y,aggregate});assert.equal(expectedSparklineMarks(f,[]),n);assert.equal(draw(f).r.summary.marks,n);}
});
// Plants change copies of the source: each red must name an assertion the live implementation makes green.
test("G2/G3/G4/G6/G7 targeted mutations are detected",()=>{
  for(const [from,to,check] of [
    ['if (lo !== null && m.layout.domain === null && !m.layout.log)', 'if (false)',R=>{const d=draw(FX.rising,400,200,{},R);near(d.hand.value.scale(12),d.hand.plot.y+d.hand.plot.h);} ],
    ['else m.sparkFlat = true;', 'else m.sparkFlat = false;',checkFlat],
    ['else m.sparkFlat = true;', 'else m.layout.domain = [a - (a === 0 ? 1 : Math.abs(a)/2), a + (a === 0 ? 1 : Math.abs(a)/2)];',checkFlat],
    ['isFinite(span) ?', 'true ?',extremeSpan],
    ['if (m.sparkFill)', 'if (false)',fillCheck],
    ['plot.y, bottom] : null','plot.y + 12, bottom] : null',fillCheck],
    ['row[z].text].concat(fields)','cells.last.text].concat(fields)',cardCheck],
    ['index.datum.push(z);','index.datum.push(index.at.length - 1);',R=>{const d=scene(fixture([5,7,null,9,11]),400,200,0,"key",R);let t=null;for(let i=0;i<3;i++)t=I.keyTarget(d.model,t,"ArrowRight").target;assert.equal(t.datum,3);} ],
    ['hand.reach.kept / hand.reach.asked','1',dotSweep],
    ['m.layout.last = m.sparkLast === "value"','m.layout.last = m.sparkLast !== "none"',R=>assert.equal(labels(draw(FX["dot-only"],160,32,{},R).r).length,0)],
  ]){const R=makeEngine(s=>plant(s,KEY,from,to));assert.throws(()=>check(R),undefined,from);}
});

test("A.4 LA2 each render token changes its named field and other tokens keep the picture",()=>{
  const tokens={"--chart-series-1":[255,0,153],"--chart-canvas":[238,238,238],"--chart-ink":[34,34,34],"--chart-title-weight":700,"--chart-line":3,"--chart-gap":4,"--chart-area-top":.6,"--chart-area-bottom":.1,"--chart-text":14,"--chart-point":6};
  for(const [w,h] of [[160,32],[400,200]]){
    const base=draw(FX.rising,w,h).r;
    for(const [key,value] of Object.entries(tokens)) {
      const changed=draw(FX.rising,w,h,{theme:{base:"light",tokens:{[key]:value}}}).r;
      assert.notEqual(JSON.stringify(changed.primitives),JSON.stringify(base.primitives),key);
      const expected=Array.isArray(value)?"#"+value.map(v=>v.toString(16).padStart(2,"0")).join(""):value;
      const dot=dots(changed)[0],line=paths(changed).find(p=>p.stroke),fill=paths(changed).find(p=>p.fill),label=labels(changed)[0];
      if(key==="--chart-series-1") {assert.equal(dot.color,expected);assert.equal(line.strokeColor,expected);assert.equal(fill.color,expected);}
      if(key==="--chart-canvas")assert.equal(dot.strokeColor,expected);
      if(key==="--chart-ink")assert.equal(label.color,expected);
      if(key==="--chart-title-weight")assert.equal(label.weight,value);
      if(key==="--chart-line")assert.equal(line.strokeWidth,value);
      if(key==="--chart-gap")assert.equal(dot.strokeWidth,value);
      if(key==="--chart-area-top")assert.equal(fill.fade[0],value);
      if(key==="--chart-area-bottom")assert.equal(fill.fade[1],value);
      if(key==="--chart-text")assert.equal(label.size,value);
      if(key==="--chart-point")assert.equal(dot.r,value-.5);
    }
  }
});
test("A.5 no cut text, no legend; End then Right stays at the last point",()=>{
  let pictures=0;
  for(const f of [FX.rising,FX.flat,FX["negative-unit"],FX["null-gap"],FX["one-point"]])for(const unit of [undefined,"ms","very long unit"])for(const last of ["none","dot","value"])for(const [w,h] of [[80,24],[160,32],[240,48],[400,200],[480,300],[960,540]]){
    const fx=clone(f);fx.document.config.mark.last=last;fx.document.config.encoding.y.unit=unit;
    const {r}=draw(fx,w,h),measure=E.measure.measurer(null).fn,m=I.modelOf(E,r,measure);assert.equal(m.cuts.length,0);assert.equal(m.entries.length,0);
    let t=I.keyTarget(m,null,"ArrowRight").target;t=I.keyTarget(m,t,"End").target;assert.equal(I.keyTarget(m,t,"ArrowRight").target.datum,t.datum);clean(r,w,h);pictures++;
  }
  console.log(`A.5 text/key sweep: ${pictures} pictures`);assert.equal(pictures,270);
});
test("G2 span plants, G4 index/unit plants and G6 frame fit plants go red",()=>{
  for(const [file,from,to,check] of [
    [KEY,'return plot.x + plot.w * fraction;','return hand.x ? hand.x.scale(x.at) : hand.band.start + hand.band.step * (ordinal + 0.5);',checkSpan],
    [KEY,'if (first === last) return plot.x + plot.w;','first = 0; last = n - 1; if (first === last) return plot.x + plot.w;',checkSpan],
    [KEY,'axis: "x", plot: plot, series: [index]','plot: plot, marks: []',cardCheck],
    ['16-lines.js','withUnit(T.value(xe.at, x.format), xUnit)','T.value(xe.at, x.format)',R=>{const f=fixture([12,30,40],[1,2,3]);f.document.config.encoding.x.unit="km";assert.equal(scene(f,480,320,1,"key",R).sc.prims.find(p=>p.k==="text").text,"2 km");}],
    ['18a-bare.js','H >= line &&','true &&',fitCheck],
    ['18a-bare.js','last !== null && fits(W, H, w, inset, gap, line)','last !== null && lvl !== "minimal" && fits(W, H, w, inset, gap, line)',fitCheck],
    ['18a-bare.js','W - 2 * inset - gap - w >= W / 2','W >= 2 * inset + gap + w',fitCheck],
  ]){
    const R=makeEngine(s=>plant(s,file,from,to));assert.throws(()=>check(R),undefined,from);
  }
});

test("G9 hostile value/unit text is escaped; an unescaped writer copy is red",()=>{
  function check(R) {
    const {r}=draw(FX.hostile,960,540,{},R),svg=R.writeString(r.primitives,"standalone","light");
    assert.ok(svg.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));assert.ok(svg.includes('&quot;q&quot;'));assert.ok(!svg.includes('<script>'));
  }
  check(E);
  const R=makeEngine(s=>plant(s,'31-write-string.js','k.text !== null ? escape(k.text) : ""','k.text !== null ? k.text : ""'));
  assert.throws(()=>check(R));
});
test("A.4 exact values and units retain their exact last text",()=>{
  const f=fixture(["9007199254740991","9007199254740992","9007199254740993"],undefined,{format:"integer",unit:"USD"});f.types.v="BIGINT";
  assert.equal(draw(f,960,540).m.layout.last,"9,007,199,254,740,993 USD");
  const count=fixture([null,null,null],[0,0,1],{aggregate:"count"});delete count.document.config.encoding.y.field;
  assert.equal(expectedSparklineMarks(count,[]),2);assert.equal(draw(count).r.summary.marks,2);
});
test("A.5 flat-series crosshair dot is at the plot middle at the numeric extremes",()=>{
  for(const a of [0,5,-5,1e-300,1.1e308,1.7e308,-1.7e308,Number.MAX_VALUE,-Number.MAX_VALUE,Number.MIN_VALUE,-Number.MIN_VALUE])for(const values of [[a],[a,a,a,a,a,a],[a,a,null,a,a,a]])for(const [w,h] of [[160,32],[80,24],[400,200]]){
    const d=scene(fixture(values),w,h,0);near(d.sc.prims.find(p=>p.k==="circle"&&p.role==="guide").cy,d.hand.plot.y+d.hand.plot.h/2);
  }
});
test("A.4 other chart tokens leave the rest picture unchanged",()=>{
  const active=["--chart-series-1","--chart-canvas","--chart-ink","--chart-title-weight","--chart-line","--chart-gap","--chart-area-top","--chart-area-bottom","--chart-text","--chart-point"];
  const base=draw().r;let checked=0;
  for(const [name,spec] of Object.entries(E.theme.TOKENS)){
    if(!name.startsWith("--chart-")||active.includes(name))continue;
    const value=spec[0]==="colour"||spec[0]==="opaque"?[17,18,19]:spec[1]===spec[3]?spec[4]:spec[3];
    const changed=draw(FX.rising,160,32,{theme:{base:"light",tokens:{[name]:value}}}).r;
    eq(changed.primitives,clone(base.primitives));checked++;
  }
  console.log(`A.4 inactive chart tokens: ${checked}`);assert.ok(checked>=45);
});

test("G3/G7 look-field, interaction-field, dot and cap plants go red",()=>{
  function fieldChanges(R,name,value) {
    const before=JSON.stringify(draw(FX.rising,480,320,{},R).r);
    R.tables.LOOK[name].value=value;
    assert.notEqual(JSON.stringify(draw(FX.rising,480,320,{},R).r),before,name);
  }
  function interactionUnchanged(R,name) {
    const before=JSON.stringify(draw(FX.rising,480,320,{},R).r);
    R.tables.LOOK[name].value*=2;
    assert.equal(JSON.stringify(draw(FX.rising,480,320,{},R).r),before,name);
  }
  for(const [from,to,name,value] of [['look("areaFade") === true','true','areaFade',false],['look("sparkLastText")','"Last"','sparkLastText','Latest']]){
    fieldChanges(makeEngine(),name,value);const R=makeEngine(s=>plant(s,KEY,from,to));assert.throws(()=>fieldChanges(R,name,value),undefined,name);
  }
  for(const name of ["crosshairDot","markMove"]){
    interactionUnchanged(makeEngine(),name);
    const R=makeEngine(s=>plant(s,KEY,'R.scales.r2((theme.n("--chart-point") - look("sparkDotShrink")) * share)','R.scales.r2((theme.n("--chart-point") - look("sparkDotShrink") + look("'+name+'")) * share)'));
    assert.throws(()=>interactionUnchanged(R,name),undefined,name);
  }
  for(const [from,to,check] of [
    ['R.scales.r2((theme.n("--chart-point") - look("sparkDotShrink")) * share)','3.5',R=>assert.equal(dots(draw(FX.rising,160,32,theme(6),R).r)[0].r,5.5)],
    ['m.sparkLast !== "none" && end !== null','m.sparkLast !== "none" && end !== null && theme.n("--chart-point") < 16',R=>assert.equal(dots(draw(FX["one-point"],400,200,theme(16),R).r).length,1)],
  ]){check(makeEngine());const R=makeEngine(s=>plant(s,KEY,from,to));assert.throws(()=>check(R));}
  for(const key of ["points","rows"]){
    const R=makeEngine(s=>{const from=s['06-tables.js'].split('\n').find(l=>l.includes('"sparkline.'+key+'":'));plant(s,'06-tables.js',from,from.replace('"sparkline.'+key+'"','"unused.sparkline.'+key+'"'));});
    const check=key==="points"?R=>assert.ok(R.tables.CAPS['sparkline.points'],'sparkline.points'):R=>assert.equal(draw(fixture(Array.from({length:10001},(_,i)=>i)),160,32,{},R).r.state,'refused');
    check(makeEngine());assert.throws(()=>check(R));
  }
});

test("A.5 the full keyboard plan and nearest-point plot boundaries",()=>{
  const {model,hand}=scene(FX.rising,160,32,0);let t=null;
  for(const [key,datum] of [["ArrowRight",0],["ArrowRight",1],["ArrowRight",2],["ArrowLeft",1],["Home",0],["End",11],["ArrowRight",11],["ArrowDown",11],["ArrowUp",11]]){t=I.keyTarget(model,t,key).target;assert.equal(t.datum,datum);}
  const p=hand.plot,y=p.y+p.h/2;
  for(const [x,datum] of [[p.x,0],[p.x+p.w,11],[p.x-3,null],[p.x+p.w+3,null]]){const hit=I.lookup(E,model,{x,y});assert.equal(hit.mark?hit.mark.datum:null,datum);}
  for(const y of [p.y-1,p.y+p.h+1])assert.equal(I.lookup(E,model,{x:p.x+10,y}).mark,null);
});

test("A.5 exact extrema distinguish values that share one float and keep the first true tie",()=>{
  const banks=[
    [['9007199254740992','9007199254740993'],'9007199254740993','9007199254740992'], [['9007199254740993','9007199254740992'],'9007199254740993','9007199254740992'],
    [['-9007199254740992','-9007199254740993'],'-9007199254740992','-9007199254740993'], [['-9007199254740993','-9007199254740992'],'-9007199254740992','-9007199254740993'],
    [['12345678901234567890.1','12345678901234567890.2'],'12345678901234567890.2','12345678901234567890.1'], [['12345678901234567890.2','12345678901234567890.1'],'12345678901234567890.2','12345678901234567890.1']];
  for(const [values,hi,lo] of banks){const f=fixture(values,undefined,{format:'integer'});f.types.v=values[0].includes('.')?'BIGDECIMAL':'BIGINTEGER';const {m}=draw(f,960,540);assert.equal(m.sparkCells.hi.exact,hi);assert.equal(m.sparkCells.lo.exact,lo);}
  const equal=draw(fixture(['5.0','5.00']));assert.equal(equal.m.sparkCells.hi.row,0);assert.equal(equal.m.sparkCells.lo.row,0);
  const {r}=draw(fixture(['9007199254740992','9007199254740993'],undefined,{format:'integer'}),960,540);assert.equal(r.index.series[0].tip[0][3],'Highest: 9,007,199,254,740,993');
});
