import {test} from 'node:test';
import assert from 'node:assert/strict';
import {restTreeSource,safeHref} from '../../main/resources/static/js/tree/rest-source.mjs';
import {effectiveWidth} from '../../main/resources/static/js/rail-resize.mjs';
import {assetPath} from '../../main/resources/static/js/page-assets.mjs';

const options={context:{workspace:'ws'},root:'scope'};
const response=data=>({ok:true,headers:{get:()=> 'application/json'},async json(){return {schema_version:1,data}}});
for(const family of ['pipelines','templates','dashboards','visualizations','parameter-sets']) {
  test(`${family} adapter binds parent, mode, root, query, workspace and no-store`,async()=>{
    let seen;
    const source=restTreeSource(family,async(url,init)=>{seen={url,init};return response({family,mode:'browse',root:'scope',parent:'scope',query:null,
      workspace_id:'ws',view_token:'members',nodes:[{key:'folder:scope/a',parent_key:null,kind:'folder',name:'a',path:'scope/a',has_children:true}],next_cursor:null});});
    const answer=await source.loadChildren(null,null,options,new AbortController().signal);
    assert.equal(answer.nodes[0].parentKey,null);assert.equal(answer.context.viewToken,'members');
    assert.equal(seen.init.cache,'no-store');assert.equal(seen.init.credentials,'same-origin');
    assert.equal(seen.url,`/api/v1/${family}/tree?root=scope&parent=scope`);
    const foreign=restTreeSource(family,async()=>response({family,mode:'browse',root:'scope',parent:'scope',query:null,workspace_id:'other',view_token:'members',nodes:[],next_cursor:null}));
    await assert.rejects(foreign.loadChildren(null,null,options),error=>error.resetTree===true);
    const expired=restTreeSource(family,async()=>({ok:false,status:400}));
    await assert.rejects(expired.loadChildren('folder:scope/a','old-process-cursor',options),error=>error.resetTree===true);
  });
}
test('unsafe destinations and dependency paths refuse schemes, traversal and foreign routes',()=>{
  for(const href of ['https://elsewhere/pipelines/id','//elsewhere/pipelines/id','/templates/id','/pipelines/../admin','/pipelines/%2e%2e/admin','/pipelines/.%2e/admin','/pipelines/%invalid','/pipelines/%2fadmin','/pipelines/id?data=1','/pipelines/id#x'])assert.equal(safeHref(href,'pipelines'),false);
  assert.equal(safeHref('/pipelines/id','pipelines'),true);
  for(const path of ['https://elsewhere/js/code.js','/js/../secret.js','/api/v1/x.js','/js/code.js?url=secret'])assert.throws(()=>assetPath(path));
  assert.equal(assetPath('/vendor/plotly/plotly-3d.min.js'),'/vendor/plotly/plotly-3d.min.js');
});
test('temporary viewport clamps do not impose a main-content minimum',()=>{
  assert.equal(effectiveWidth(1440,1440,232),1440);assert.equal(effectiveWidth(932,800,232),800);
  assert.equal(effectiveWidth(932,1440,232),932);assert.equal(effectiveWidth(1,1440,232),232);
});
