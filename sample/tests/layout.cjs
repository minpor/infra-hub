const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',args:['--no-sandbox','--disable-dev-shm-usage']});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 const url=pathToFileURL(path.join(__dirname,'../index.html')).href;
 try{
  for(const width of [1440,1280,768,390,320]){
   await page.setViewportSize({width,height:1000});
   for(const route of ['projects','builds','project/aukni/pipeline','build/148','infra/servers/prod-01','mcp/access','mcp/external']){
    await page.goto(url+'#'+route);await page.locator('#main h1').waitFor();await page.evaluate(()=>document.fonts.ready);
    const dimensions=await page.evaluate(()=>({width:innerWidth,content:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll('body *')].filter(el=>{if(el.closest('.table-wrap'))return false;const r=el.getBoundingClientRect();return r.width>0&&(r.right>innerWidth+2||r.left< -2)}).slice(0,6).map(el=>el.className?.baseVal||el.className)}));
    assert(dimensions.content<=width+1,`${width}/${route}: ${JSON.stringify(dimensions)}`);
    assert.equal(dimensions.overflow.length,0,`${width}/${route} clipped: ${JSON.stringify(dimensions)}`);
    if((width===1440&&['projects','project/aukni/pipeline','mcp/access'].includes(route))||(width===390&&route==='projects'))await page.screenshot({path:'/tmp/infra-sample-layout-'+width+'-'+route.replaceAll('/','-')+'.png',fullPage:true});
   }
   await page.goto(url+'#projects');await page.locator('[data-action=new-project]').click();await page.getByRole('dialog').waitFor();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'Modal overflows '+width);await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);
  }
  assert.deepEqual(errors,[]);console.log('PASS: all screens and modal at 1440, 1280, 768, 390, 320px; no page errors');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
