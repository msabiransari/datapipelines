// Gauge (#1075): independent row, geometry and interaction checks. No production diagnostics are exposed.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, inputOf, chartFixtures, MODULE } from "./engine.mjs";
import { violations, WIDTHS, HEIGHTS } from "./geometry.mjs";
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === "gauge").map(f => [f.name, f.fixture]));
const R = engine();
const plain = v => JSON.parse(JSON.stringify(v));
const fx = (name = "plain", edit = () => {}) => { const f = structuredClone(FX[name]); edit(f, f.document.config.encoding.value, f.document.config.mark); return f; };
const draw = (f, w = 360, h = 240, extra = {}) => R.render(inputOf(f, w, h, extra));
const paths = r => r.primitives.filter(p => p.k === "path");
const mark = r => paths(r).find(p => p.role === "mark");
const text = r => r.primitives.filter(p => p.k === "text").map(p => p.text);

test("A.2: each aggregate reads every row, gaps stay gaps and count needs no field", () => {
  for (const [agg, vals, want] of [["sum",[10,20,30],60],["mean",[10,20,30],20],["mean",[10,null,30],20],["min",[10,20,30],10],["max",[10,20,30],30],["count",[null,null],2]]) {
    const r = draw(fx("plain", (f,e) => { f.columns.v = vals; e.aggregate = agg; if (agg === "count") delete e.field; }));
    assert.equal(r.state, "rendered"); assert.equal(r.summary.marks, 1);
    assert.equal(r.index.marks[0].card.rows[0].value, String(want));
  }
  for (const [agg,vals] of [["sum",[]],["sum",[null]],["count",[]]]) {
    const r = draw(fx("plain", (f,e) => { f.columns.v=vals; e.aggregate=agg; if(agg === "count") delete e.field; }));
    assert.equal(r.state,"no-data"); assert.deepEqual(plain(r.summary.warnings),[]);
  }
  for (const [vals,domain] of [[[1e308,1e308],[0,100]],[[1],[-1e308,1e308]],[[1],[1,1]]]) {
    const r=draw(fx("plain",(f,e)=>{f.columns.v=vals;e.scale.domain=domain;}));
    assert.equal(r.state,"no-data"); assert.deepEqual(plain(r.summary.warnings.map(w=>w.code)),["chart.rule.non-finite"]);
  }
});

test("A.3: minimum arc, high clamp, track and value paint, lower band owns a border", () => {
  for (const [v,word] of [[0,"Bad"],[49.99,"Bad"],[50,"Bad"],[50.01,"Watch"],[80,"Watch"],[80.01,"Good"],[130,"Good"],[-30,"Bad"]]) {
    const r=draw(fx("bands-target",f=>f.columns.v=[v]));
    assert.ok(text(r).includes(word)); assert.equal(mark(r).stroke,"--chart-series-1"); assert.equal(mark(r).round,true);
    assert.ok(mark(r).d.includes("A"));
    assert.equal(paths(r).find(p=>p.role === "grid").stroke,"--chart-grid");
    if(v>=100) assert.equal(mark(r).d.filter(x=>x === "A").length,2);
  }
});

test("A.4: exact digits, number clamping, long values and minimal draw no text", () => {
  for (const [type,value,want] of [["BIGINTEGER","123456789012345678","123,456,789,012,345,678"],["BIGDECIMAL","1234567.890123456789","1,234,567.89"]]) {
    const r=draw(fx("plain",(f,e)=>{f.columns.v=[value];f.types.v=type;e.scale.domain=[0,1e20];}));
    assert.equal(r.index.marks[0].card.rows[0].value,want);
  }
  for (const [w,h,size] of [[960,540,30],[360,240,30],[240,140,18]]) {
    const r=draw(FX["bands-target"],w,h);assert.equal(r.primitives.find(p=>p.k === "text" && p.role === "mark").size,size);
  }
  assert.equal(text(draw(FX["bands-target"],80,24)).length,0);
});

test("A.5: every fixture pins lookup row zero, keys are keys alone and never switches", () => {
  for (const f of Object.values(FX)) {
    const r=draw(f);
    for (const m of r.index.marks || []) { assert.equal(m.row,0);assert.equal(m.series,"measure");assert.equal(m.datum,0);assert.equal(m.emphasis,null);assert.equal(m.card.keep,0); }
  }
  for(const name of ["one-band","target-only"]) assert.ok(draw(FX[name]).primitives.some(p=>p.role === "legend"));
});

test("A.6: the gauge fixtures satisfy invariants at every sweep frame", () => {
  let pictures=0;
  for(const f of Object.values(FX)) for(const w of WIDTHS) for(const h of HEIGHTS) {
    const r=draw(f,w,h);assert.deepEqual(plain(violations(r.primitives,w,h).violations),[],`${f.note} ${w}x${h}`);pictures++;
  }
  assert.equal(pictures,15*35*5);console.log(`gauge sweep: ${pictures} pictures`);
});

const I = createRequire(import.meta.url)(join(MODULE,"../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const KEYS=["ArrowRight","ArrowLeft","ArrowDown","ArrowUp","Home","End"];
test("A.5: interaction keys stay on the one mark; a cut title is walked and returns",()=>{
  for(const [w,h] of [[360,240],[240,140],[80,24]]) {
    const r=draw(FX["bands-target"],w,h), model=I.modelOf(R,r,R.measure.measurer(null).fn), target={kind:"mark",id:"measure",datum:0};
    for(const k of KEYS) assert.deepEqual(plain(I.keyTarget(model,target,k).target),target);
    assert.equal(model.entries.length,0);
  }
  const r=draw(fx("bands-target",f=>f.document.config.title.text="A very long title ".repeat(10))), model=I.modelOf(R,r,R.measure.measurer(null).fn);
  const t={kind:"mark",id:"measure",datum:0}, cut=I.keyTarget(model,t,"ArrowRight").target;
  assert.equal(cut.kind,"cut");assert.deepEqual(plain(I.keyTarget(model,cut,"ArrowLeft").target),t);
});

// A spy is installed only in this test's copy, so geometry expectations use the handed plot.
function spied(edit) {
  const e=engine(s=>{
    if(edit) edit(s);
    const key="types/gauge.js", from='model: model, marks: marks, axes: "polar"';
    assert.equal(s[key].split(from).length,2);
    s[key]=s[key].replace(from,'model: model, marks: function(m,hand,h) { R.seen={m:m,hand:hand}; return marks(m,hand,h); }, axes: "polar"');
  });
  return e;
}
const S=spied();
function geom(f,w,h,line=2,e=S) {
  const r=e.render(inputOf(f,w,h,{theme:{base:"light",tokens:{"--chart-line":line}}}));
  return {r,plot:e.seen.hand.plot};
}
function corners(p) {
  const dx=p.x2-p.x1,dy=p.y2-p.y1,len=Math.hypot(dx,dy),nx=-dy/len*p.strokeWidth/2,ny=dx/len*p.strokeWidth/2;
  return [[p.x1+nx,p.y1+ny],[p.x1-nx,p.y1-ny],[p.x2+nx,p.y2+ny],[p.x2-nx,p.y2-ny]];
}
function targetFailures(e=S) {
  const bad=[],counts={};
  for(const line of [2,5,6,8]) {
    counts[line]=0;
    for(const w of [80,120,200,240,280,320,360,480,640,960,1280,1600]) for(const h of [60,100,140,200,240,320,480,540]) for(const banded of [false,true]) {
      let radius=null;
      for(const target of [0,100,87.5,50,130,-4]) {
        const f=fx("plain",(f,v,m)=>{m.target=target;if(banded)m.bands=FX["bands-target"].document.config.mark.bands;});
        const {r,plot}=geom(f,w,h,line,e),lines=r.primitives.filter(p=>p.k === "line" && p.role === "guide");
        if(lines.length===0) continue;
        counts[line]++;
        const track=paths(r).find(p=>p.role === "grid"),ra=track.d[4];
        if(radius !== null && radius !== ra) bad.push("radius depends on target");radius=ra;
        const b=r.index.marks[0].shape;
        for(const p of lines) for(const [x,y] of corners(p)) {
          if(x<plot.x-.02||x>plot.x+plot.w+.02||y<plot.y-.02||y>plot.y+plot.h+.02)bad.push(`${line} ${w}x${h} target ${target} outside plot`);
          if(x<b.x-.02||x>b.x+b.w+.02||y<b.y-.02||y>b.y+b.h+.02)bad.push("target outside lookup");
        }
      }
    }
  }
  return {bad,counts};
}
test("A.3: target stroke's four corners fit plot and lookup at every admitted width, same radius at every angle",()=>{
  const {bad,counts}=targetFailures();assert.deepEqual(bad.slice(0,10),[]);
  for(const n of Object.values(counts))assert.ok(n>=1000,String(n));console.log("target pictures by width",counts);
});

function endAngle(p,cx,cy) { return Math.atan2(p.d.at(-2)-cx,cy-p.d.at(-1))*180/Math.PI; }
test("A.3: huge finite spans take the fraction first; seeded angles have small integer oracles",()=>{
  let large=0;
  for(let k=0;k<200;k++) {
    const lo=k%7-3, span=20+k%80, offset=k%(span+1), value=lo+offset;
    const f=fx("plain",(f,e,m)=>{f.columns.v=[value*1e306];e.scale.domain=[lo*1e306,(lo+span)*1e306];m.target=(lo+span*.75)*1e306;});
    const {r}=geom(f,480,320),p=mark(r),track=paths(r).find(p=>p.role === "grid"), ra=track.d[4];
    const cx=(track.d[1]+track.d.at(-2))/2, cy=track.d[2]-ra/2;
    const expected=Math.max(-118.5,-120+240*offset/span);
    assert.ok(Math.abs(endAngle(p,cx,cy)-expected)<.04,`${endAngle(p,cx,cy)} vs ${expected}`);
    assert.equal(r.state,"rendered"); assert.equal(r.summary.warnings.length,0);
    if(!Number.isFinite(240*offset*1e306))large++;
  }
  assert.ok(large>=100);console.log(`huge-offset angle cases: ${large}`);
});

test("A.6: row oracle directly handles count without field, nulls and overflowing domains", async()=>{
  const {expectedGaugeMarks}=await import("./geometry.mjs");
  const cases=[["count",[null,null],1],["count",[],0],["count",Array(7).fill(null),1],["sum",[],0],["sum",[null],0],["sum",[10,20,30],1],["mean",[10,null,30],1],["min",[1,2],1],["max",[1,2],1],["sum",[1e308,1e308],0],["sum",[0],1],["sum",[-1],1],["mean",[null,null],0],["count",[null,null,null],1],["sum",[13],1]];
  for(const [agg,vs,want] of cases) {
    const f=fx("plain",(f,e)=>{f.columns.v=vs;e.aggregate=agg;if(agg==="count")delete e.field;});
    assert.equal(expectedGaugeMarks(f),want);assert.equal(draw(f).summary.marks,want);
  }
});

const own = r => r.primitives.filter(p=>(p.role === "grid" && p.k === "path") || (p.role === "guide" && p.k !== "rect") || p.role === "mark" || p.role === "tick");
test("A.4 LA2: each gauge token reaches only its named fields, with both levels observed full",()=>{
  const changes={"--chart-grid":1,"--chart-series-1":1,"--chart-good":1,"--chart-warn":1,"--chart-bad":1,"--chart-canvas":1,"--chart-ink":2,"--chart-ink-2":1,"--chart-muted":2,"--chart-title-weight":1,"--chart-line":1};
  let compared=0;
  for(const [w,h] of [[960,540],[480,320]]) for(const [token,spec] of Object.entries(R.theme.TOKENS)) {
    if(!token.startsWith("--chart-"))continue;
    const value=spec[0] === "colour" || spec[0] === "opaque" ? [23,45,67] : token === "--chart-title-weight" ? 700 : token === "--chart-text" ? 14 : token === "--chart-text-small" ? 13 : token === "--chart-title" ? 16 : token === "--chart-line" ? 4 : spec[0] === "length" ? (spec[1] === spec[4] ? spec[3] : spec[4]) : spec[0] === "duration" ? spec[1]+1 : spec[0] === "opacity" ? .3 : spec[1];
    const f=fx("bands-target",(f,e)=>e.unit="ms"),a=draw(f,w,h),b=draw(f,w,h,{theme:{base:"light",tokens:{[token]:value}}});
    assert.equal(a.level,"full");assert.equal(b.level,"full");compared++;
    const aa=own(a),bb=own(b),diff=aa.flatMap((p,i)=>JSON.stringify(p)===JSON.stringify(bb[i])?[]:[{p,fields:Object.keys(p).filter(k=>JSON.stringify(p[k])!==JSON.stringify(bb[i][k]))}]);
    if(token in changes) {
      assert.equal(diff.length,changes[token],token);
      const fields=token === "--chart-title-weight"?["weight"]:token === "--chart-line"?["strokeWidth"]:token === "--chart-ink-2"||token === "--chart-muted"?["color"]:["strokeColor"];
      for(const d of diff)assert.deepEqual(d.fields,d.p.k === "text" && token !== "--chart-title-weight"?["color"]:fields,token);
    } else if(["--chart-text","--chart-text-small","--chart-title"].includes(token)) assert.ok(diff.length>0,token);
    else assert.equal(diff.length,0,token);
  }
  console.log(`LA2 comparisons: ${compared}, both results full`);
  for(const [w,h,from,to] of [[400,280,"full","compact"],[360,240,"full","compact"],[240,140,"compact","minimal"]]) for(const [token,value] of [["--chart-text",14],["--chart-text-small",13],["--chart-title",16]]) {
    assert.equal(draw(FX["bands-target"],w,h).level,from);assert.equal(draw(FX["bands-target"],w,h,{theme:{base:"light",tokens:{[token]:value}}}).level,to);
  }
});

function mutate(from,to) {
  return engine(s=>{const k="types/gauge.js";assert.equal(s[k].split(from).length,2,from);s[k]=s[k].replace(from,to);});
}
const rd=(e,f=FX["bands-target"],w=360,h=240)=>e.render(inputOf(f,w,h));
test("G2 G3 G4 G5 G6 G7: source plants are observed red on the property and pristine green",()=>{
  const checks=[
    ["mean",'D.aggregate(vals, e.aggregate, e.field)','D.aggregate(vals, e.aggregate === "mean" ? "max" : e.aggregate, e.field)',f=>f.document.config.encoding.value.aggregate="mean",r=>r.index.marks[0].card.rows[0].value === "20"],
    ["zone border",'v <= m.bands[i].to','v < m.bands[i].to',f=>f.columns.v=[50],r=>r.primitives.some(p=>p.role === "mark" && p.k === "text" && p.text === "Bad")],
    ["minimum sweep",'look("gaugeMinSweep")','0',f=>f.columns.v=[0],r=>mark(r).d.includes("A")],
    ["row zero",'datum: 0, row: 0','datum: 0, row: 3',()=>{},r=>r.index.marks[0].row === 0],
    ["one key",'m.keysAlone = true','m.keysAlone = false',f=>{f.document.config.mark={type:"gauge",target:70};},r=>r.primitives.some(p=>p.role === "legend")],
    ["plot hit box",'x: r2(cx - outer), y: r2(top), w: r2(2 * outer), h: r2(used)','x: plot.x, y: plot.y, w: plot.w, h: plot.h',()=>{},r=>r.index.marks[0].shape.w < r.index.plot.w],
  ];
  for(const [name,from,to,edit,check] of checks) {
    const f=fx("bands-target",edit);if(name === "mean")f.columns.v=[10,20,30];
    assert.equal(check(rd(R,f)),true,name+" green");assert.equal(check(rd(mutate(from,to),f)),false,name+" red");console.log(`plant ${name}: RED -> GREEN`);
  }
  const wide=spied(s=>s['types/gauge.js']=s['types/gauge.js'].replace('Math.max(look("gaugeTargetBack"), theme.n("--chart-line"))','look("gaugeTargetBack")'));
  const bad=targetFailures(wide).bad;assert.ok(bad.some(x=>x.startsWith("6 ")));assert.ok(bad.some(x=>x.startsWith("8 ")));assert.ok(!bad.some(x=>x.startsWith("2 ")||x.startsWith("5 ")));console.log("plant target wide: RED at 6/8, GREEN at 2/5; corrected all GREEN");
});

test("A.3: radius is maximal on the 0.01 grid; tiny decor drops; ring pieces end at their actual border",()=>{
  for(const [w,h] of [[960,540],[360,240],[240,140],[160,160],[120,60],[80,24]]) {
    const {r,plot}=geom(FX["bands-target"],w,h),track=paths(r).find(p=>p.role === "grid"),ra=track.d[4],small=r.level !== "minimal";
    const decorated=r.primitives.some(p=>p.k === "line" && p.role === "guide");
    assert.equal(decorated,!(w===80 && h===24));
    function fits(rad) {
      const thick=Math.min(Math.max(10,Math.min(16,.17*rad)),.5*rad),edge=rad+thick/2;
      const ext=decorated?Math.max(7,Math.hypot(edge+3,2.5)-edge):0;
      const below=Math.max(.5*rad+thick/2+(small?4+11*1.362:0),decorated?.5*(edge+5)+2:0,decorated?.5*(edge+3)+Math.sin(2*Math.PI/3)*2.5:0);
      return edge+ext<=plot.w/2+1e-8 && edge+ext+below<=plot.h+1e-8;
    }
    assert.equal(fits(ra),true,`${w}x${h} fits`);assert.equal(fits(ra+.01),false,`${w}x${h} maximal`);
  }
  for(const ends of [[50],[50,80],[50,80,100],[80,50,100],[25,50,75,100],[50,150],[-5,50],[50,99.8,100],[0,100]]) {
    const f=fx("plain",(f,e,m)=>m.bands=ends.map((to,i)=>({to,tone:["bad","warn","good","good"][i]}))),{r}=geom(f,480,320);
    const track=paths(r).find(p=>p.role === "grid"),cx=(track.d[1]+track.d.at(-2))/2,cy=track.d[2]-track.d[4]/2;
    let run=0;const intervals=[];
    for(const to of ends){if(Math.min(to,100)>run)intervals.push([run,Math.min(to,100)]);run=Math.max(run,to);}
    const expected=intervals.map(([a,b],i)=>[-120+2.4*a+(i?1.2:0),-120+2.4*b-(i<intervals.length-1?1.2:0)]).filter(([a,b])=>b>a);
    const ring=paths(r).filter(p=>p.role === "guide");assert.equal(ring.length,expected.length);
    ring.forEach((p,i)=>{assert.ok(Math.abs(Math.atan2(p.d[1]-cx,cy-p.d[2])*180/Math.PI-expected[i][0])<.04);assert.ok(Math.abs(endAngle(p,cx,cy)-expected[i][1])<.04);});
  }
});

test("A.5 T14: card texts and one-step key plan at the smallest frame are measured through the layer",()=>{
  for(const [w,h,want] of [[80,24,["Now","62"]],[120,60,["Uptime","Now","62","+4 more"]],[240,140,["Uptime","Now","62","Target","70","Bad","to 50","Watch","to 80","Good","to 100"]],[360,240,["Uptime","Now","62","Target","70","Bad","to 50","Watch","to 80","Good","to 100"]]]) {
    const r=draw(FX["bands-target"],w,h),width=R.measure.measurer(null).fn,model=I.modelOf(R,r,width),theme=R.theme.resolve("light"),m=I.measures(R,"light"),frame={w,h};
    const card=I.markCard(R,width,I.markLines(model,0),null,frame,m),ps=I.cardPrims(R,card,I.place(card.box,{x:w/2,y:h/2},frame,m.dist),m,false);
    assert.deepEqual(plain(ps.filter(p=>p.k === "text").map(p=>p.text)),want);
    if(w===80)console.log("T14 minimal keys: ArrowRight -> mark0; all six keys -> mark0; card",want);
  }
});

test("G2 G3 G4 G5 G6 G7: additional value, paint, fit and card plants fail their own assertions",()=>{
  const markText=r=>r.primitives.filter(p=>p.k === "text" && p.role === "mark");
  const cases=[
    ['non-finite','!finite(a.num) || !finite(span) || span <= 0','false',f=>f.columns.v=[1e308,1e308],r=>r.state === "no-data"],
    ['null','var span = domain[1] - domain[0]','if (a.num === null) a.num = 0; var span = domain[1] - domain[0]',f=>f.columns.v=[null],r=>r.state === "no-data"],
    ['ring ends','i === 0 ? 0 : look("gaugeRingBreak")','look("gaugeRingBreak")',()=>{},r=>paths(r).find(p=>p.role === "guide").d[1] === paths(draw(FX["bands-target"],960,540)).find(p=>p.role === "guide").d[1]],
    ['short bands','var to = angle(p.to)','var to = angle(m.hi)',f=>f.document.config.mark.bands=f.document.config.mark.bands.slice(0,2),r=>{const p=paths(r).filter(p=>p.role === "guide").at(-1);return p.d.at(-1)<paths(r).find(p=>p.role === "grid").d.at(-1)-1;}],
    ['arc paint','strokeColor: theme.css("--chart-series-1"), strokeWidth: thick','strokeColor: theme.css("--chart-good"), strokeWidth: thick',()=>{},r=>mark(r).strokeColor === R.theme.resolve("light").css("--chart-series-1")],
    ['number clamp','clamp(look("gaugeNumberShare") * ra, least, look("gaugeNumberMax") * h.text)','look("gaugeNumberShare") * ra',()=>{},r=>markText(r)[0].size<=30],
    ['minimal text','hand.level !== "minimal"','true',()=>{},r=>text(r).length === 0,160,160],
    ['row one','datum: 0, row: 0','datum: 0, row: 1',()=>{},r=>r.index.marks[0].row === 0],
    ['tone literal','theme.css(TONES[p.tone])','"#123456"',()=>{},r=>paths(r).find(p=>p.stroke === "--chart-good").strokeColor === R.theme.resolve("light").css("--chart-good")],
    ['target literal','strokeColor: theme.css(s[0])','strokeColor: "#123456"',()=>{},r=>r.primitives.find(p=>p.k === "line" && p.stroke === "--chart-ink").strokeColor === R.theme.resolve("light").css("--chart-ink")],
  ];
  for(const [name,a,b,edit,check,w=960,h=540] of cases) {
    const f=fx("bands-target",edit);assert.equal(check(rd(R,f,w,h)),true,name+" green");
    let red=false;try{red=!check(rd(mutate(a,b),f,w,h));}catch{red=true;}
    assert.equal(red,true,name+" red");console.log(`plant ${name}: RED -> GREEN`);
  }
  const cap=engine(s=>s['types/gauge.js']=s['types/gauge.js'].replace('markCount: shown ? 1 : 0','markCount: shown ? 2 : 0'));
  assert.equal(rd(cap).summary.refusal.code,"chart.too_many_marks");delete cap.tables.CAPS['gauge.marks'];assert.equal(rd(cap).state,"rendered");
});

test("G4: ring bottom, tiny decor and thickness, target fit and huge angles are guarded",async()=>{
  const {markBox}=await import("./geometry.mjs");
  function strokeFailures(e) {
    let bad=0;
    for(const w of [80,120,160,240,360,480,960])for(const h of [24,40,50,60,100,140,200,240,480]) {
      const {r,plot}=geom(FX["bands-target"],w,h,2,e);
      for(const p of paths(r))if(p.role !== "mark" && p.role !== "grid" && p.role !== "guide")continue;else {
        const b=markBox(p),s=p.strokeWidth/2;if(b[0]-s<plot.x-.02||b[1]-s<plot.y-.02||b[2]+s>plot.x+plot.w+.02||b[3]+s>plot.y+plot.h+.02)bad++;
      }
    }return bad;
  }
  assert.equal(strokeFailures(S),0);
  const e=spied(s=>s['types/gauge.js']=s['types/gauge.js'].replace('if (banded) b = Math.max(b, down * (ra + t / 2 + look("gaugeRingOffset")) + look("gaugeRingWidth") / 2);',''));
  assert.ok(strokeFailures(e)>0);console.log("plant ring bottom: RED -> GREEN");
  for(const [a,b,check] of [
    ['f.ra < look("gaugeDecorMin")','f.ra < 0',r=>!r.primitives.some(p=>p.role === "guide" && p.k === "line")],
    ['ra * look("gaugeThickCap")','Infinity',r=>paths(r).find(p=>p.role === "grid").strokeWidth<=3],
  ]) {assert.equal(check(rd(R,FX["bands-target"],80,24)),true);assert.equal(check(rd(mutate(a,b),FX["bands-target"],80,24)),false);}
  const f=fx("plain",(f,e)=>{f.columns.v=[1e307];e.scale.domain=[0,1e308];});
  const bad=mutate('look("gaugeSweep") * ((clamp(v, m.lo, m.hi) - m.lo) / (m.hi - m.lo))','look("gaugeSweep") * (clamp(v, m.lo, m.hi) - m.lo) / (m.hi - m.lo)');
  assert.equal(rd(R,f).state,"rendered");assert.throws(()=>rd(bad,f),/finite angle/);
  const reach=spied(s=>s['types/gauge.js']=s['types/gauge.js'].replace('Math.sqrt(far * far + wide * wide / 4) - edge','reach').replace('if (targeted) b = Math.max(b, down * (ra + t / 2 + reach) + side * wide / 2);',''));
  assert.ok(targetFailures(reach).bad.length>0);console.log("plant target reach: RED -> GREEN; angle product: RED -> GREEN");
  const long=fx("plain",f=>f.columns.v=[1234567890123]),probe=r=>r.primitives.find(p=>p.k === "text" && p.role === "mark").size===18;
  assert.equal(probe(rd(R,long,360,240)),true);assert.equal(probe(rd(mutate('if (inside(cx, cy, cx - w / 2, cy - bh / 2, w, bh, radius))','if (true)'),long,360,240)),false);
});

test("G7 and A.4: words are live look fields; compact and percent keep their unit once",()=>{
  const e=mutate('return look(tone === "good" ? "gaugeWordGood" : tone === "warn" ? "gaugeWordWarn" : "gaugeWordBad");','return tone === "good" ? "Good" : look(tone === "warn" ? "gaugeWordWarn" : "gaugeWordBad");');
  const f=fx("bands-target",f=>f.columns.v=[90]);
  const before=rd(e,f);e.tables.LOOK.gaugeWordGood.value="Fine";assert.deepEqual(plain(rd(e,f).primitives),plain(before.primitives));
  const good=engine(),base=rd(good,f);good.tables.LOOK.gaugeWordGood.value="Fine";assert.notDeepEqual(plain(rd(good,f).primitives),plain(base.primitives));
  console.log("plant literal Good: look comparison RED -> GREEN");
  for(const [format,value,want] of [["compact",1234000,"1.2M ms"],["percent",.625,"62.5% ms"]]) {
    const f=fx("plain",(f,e)=>{f.columns.v=[value];e.format=format;e.unit="ms";e.scale.domain=[0,2000000];});
    assert.equal(draw(f).index.marks[0].card.rows[0].value,want);
  }
});
