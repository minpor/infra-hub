const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 const context=await browser.newContext({viewport:{width:1440,height:1050},offline:true,acceptDownloads:true});
 const page=await context.newPage(),errors=[],requests=[];
 page.on('pageerror',e=>errors.push(String(e)));page.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});
 const demo=pathToFileURL(path.join(__dirname,'../index.html')).href;
 const read=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('infra-hub-sample-v1')));
 const go=async route=>{await page.goto(demo+'#'+route);await page.locator('#main h1').waitFor();};
 const modal=()=>page.getByRole('dialog');
 try{
 await go('projects');await page.evaluate(()=>document.fonts.ready);
 const fonts=await page.evaluate(async()=>{const result={};for(const [family,text] of [['Onest','Проекты'],['Unbounded','Сборка'],['IBM Plex Mono','main']])result[family]=(await document.fonts.load(`500 14px "${family}"`,text)).length;return result;});
 console.log('Fonts:',fonts);assert(Object.values(fonts).every(n=>n>0));
 await page.screenshot({path:'/tmp/infra-sample-projects.png',fullPage:true});
 assert.equal(await page.locator('.nav-link[aria-current="page"]').textContent(),'Проекты');
 for(const route of ['builds','build/148','project/aukni/pipeline','infra/servers/prod-01','infra/domains','infra/databases','infra/xray','mcp/access','mcp/external','mcp/audit']){await go(route);assert(await page.locator('#main h1').textContent());}
 console.log('All screens: OK');
 await go('projects');await page.locator('[data-action="new-project"]').click();await modal().locator('[name=name]').fill('Demo <img>');await modal().locator('[name=repo]').fill('https://github.com/demo/new-project');await modal().getByRole('button',{name:'Добавить проект',exact:true}).click();
 await page.locator('h1').filter({hasText:'Demo <img>'}).waitFor();assert.equal(await page.locator('#main img').count(),0);assert.match(await page.locator('#main').innerText(),/Настройки сохранены/);
 console.log('Create project / escaped user text: OK');
 await go('project/aukni/pipeline');await page.locator('[data-action="step-add"]').click();assert.equal(await page.locator('.pipeline-step').count(),4);await page.locator('.pipeline-step').last().locator('[name=stepName]').fill('Проверка');await page.locator('.pipeline-step').last().locator('[name=command]').fill('echo demo-check');await page.locator('.pipeline-step').last().locator('[data-action=step-up]').click();assert.equal(await page.locator('.pipeline-step').nth(2).locator('[name=stepName]').inputValue(),'Проверка');
 await page.locator('#pipeline-form [name=cpu]').fill('12');await page.locator('button[name=intent][value=save]').click();await page.locator('#pipeline-error').filter({hasText:'Лимиты CPU'}).waitFor();await page.locator('#pipeline-form [name=cpu]').fill('6');await page.locator('button[name=intent][value=save]').click();await page.reload();await page.locator('.pipeline-step').nth(2).locator('[name=stepName]').waitFor();assert.equal(await page.locator('.pipeline-step').nth(2).locator('[name=stepName]').inputValue(),'Проверка');
 console.log('Pipeline editing / reordering / validation / persistence: OK');
 await go('project/aukni/variables');await page.locator('[data-action=new-variable]').click();await modal().locator('[name=name]').fill('DEMO_FLAG');await modal().locator('[name=value]').fill('enabled');await modal().getByRole('button',{name:'Сохранить',exact:true}).click();await page.reload();await page.getByText('DEMO_FLAG',{exact:true}).waitFor();
 await go('builds');await page.locator('[data-action=cancel-build][data-build="148"]').click();await page.locator('[data-action=cancel-build][data-build="147"]').click();
 await page.locator('[data-action=speed]').click();assert.equal((await read()).simulation.speed,30);
 const runBuild=async scenario=>{await page.locator('[data-action=new-build]').first().click();await modal().locator('[name=project]').selectOption('aukni');await modal().locator('details summary').click();await modal().locator('[name=scenario]').selectOption(scenario);await modal().getByRole('button',{name:'Поставить в очередь',exact:true}).click();await page.waitForURL(/#build\//);await page.locator('h1').filter({hasText:/#\d+/}).waitFor();return Number((await page.locator('h1').textContent()).match(/#(\d+)/)[1]);};
 const failId=await runBuild('tests');await page.waitForFunction(id=>JSON.parse(localStorage.getItem('infra-hub-sample-v1')).builds.find(b=>b.id===id).status==='failed',failId,{timeout:10000});assert.equal((await read()).projects.find(p=>p.id==='aukni').environments[0].commit,'c2ef9a2');
 await page.screenshot({path:'/tmp/infra-sample-failed.png',fullPage:true});console.log('Failed build preserves deployed version: OK');
 await go('builds');const successId=await runBuild('success');await page.waitForFunction(id=>JSON.parse(localStorage.getItem('infra-hub-sample-v1')).builds.find(b=>b.id===id).status==='success',successId,{timeout:10000});await page.locator('#deploy-form').waitFor();
 await page.locator('#deploy-form button[type=submit]').click();await page.locator('#deploy-error').filter({hasText:'Подтвердите'}).waitFor();await page.locator('#deploy-form [name=production]').check();await page.locator('#deploy-form button[type=submit]').click();
 await page.waitForFunction(()=>JSON.parse(localStorage.getItem('infra-hub-sample-v1')).deployments[0]?.status==='success',null,{timeout:12000});let d=await read(),newCommit=d.builds.find(b=>b.id===successId).commit;assert.equal(d.projects.find(p=>p.id==='aukni').environments[0].commit,newCommit);
 await page.locator('#deploy-form [name=production]').check();await page.locator('#deploy-form details summary').click();await page.locator('#deploy-form [name=scenario]').selectOption('health');await page.locator('#deploy-form button[type=submit]').click();await page.waitForFunction(()=>JSON.parse(localStorage.getItem('infra-hub-sample-v1')).deployments[0]?.status==='failed',null,{timeout:12000});d=await read();assert.equal(d.projects.find(p=>p.id==='aukni').environments[0].commit,newCommit);
 console.log('Production confirmation / deployment / failed health check: OK');
 await page.locator('[data-action=rollback][data-release="rel-1"]').click();await modal().getByRole('button',{name:'Подтвердить возврат',exact:true}).click();await page.locator('#modal-error').filter({hasText:'Подтвердите'}).waitFor();await modal().locator('[name=migrations]').check();await modal().locator('[name=confirmation]').check();await modal().getByRole('button',{name:'Подтвердить возврат',exact:true}).click();await page.waitForFunction(()=>JSON.parse(localStorage.getItem('infra-hub-sample-v1')).deployments[0]?.status==='success',null,{timeout:12000});assert.equal((await read()).projects.find(p=>p.id==='aukni').environments[0].commit,'c2ef9a2');
 console.log('Rollback: OK');
 await go('mcp/access');await page.locator('[data-action=revoke-client][data-client="client-1"]').click();await page.locator('[data-action=mcp-call]').click();await modal().locator('[name=client]').selectOption('client-1');await modal().getByRole('button',{name:'Смоделировать вызов',exact:true}).click();await page.waitForURL(/#mcp\/audit/);assert.equal((await read()).mcp.audit[0].allowed,false);assert.match((await read()).mcp.audit[0].reason,/отозван/);
 await page.locator('[data-action=mcp-call]').first().click();await modal().locator('[name=client]').selectOption('client-2');await modal().locator('[name=tool]').selectOption('build.start');await modal().getByRole('button',{name:'Смоделировать вызов',exact:true}).click();assert.match((await read()).mcp.audit[0].reason,/Нет права/);
 await page.locator('[data-action=mcp-call]').first().click();await modal().locator('[name=client]').selectOption('client-2');await modal().locator('[name=tool]').selectOption('build.logs');await modal().getByRole('button',{name:'Смоделировать вызов',exact:true}).click();assert.equal((await read()).mcp.audit[0].allowed,true);
 console.log('MCP revocation / denied write / allowed read / audit: OK');
 await go('mcp/external');await page.locator('[data-action=test-external]').first().click();await page.getByText('Доступен',{exact:true}).waitFor();assert.equal(requests.length,0);
 await go('infra/servers/dev-01');await page.locator('[data-action=poll-server]').click();await page.waitForFunction(()=>!JSON.parse(localStorage.getItem('infra-hub-sample-v1')).servers.find(s=>s.id==='dev-01').stale);assert.equal(requests.length,0);
 await page.locator('[data-action=search]').click();await modal().locator('#global-query').fill('aukni.example');await modal().getByRole('link').filter({hasText:'aukni.example'}).click();await page.waitForURL(/#infra\/domains/);
 console.log('External MCP / stale inventory / global search / no network requests: OK');
 await go('projects');await page.locator('[data-action=theme]').click();await page.reload();assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');await page.screenshot({path:'/tmp/infra-sample-dark.png',fullPage:true});
 for(const width of [1280,768,390,320]){await page.setViewportSize({width,height:1000});for(const r of ['projects','builds','project/aukni/pipeline','build/'+successId,'infra/servers/prod-01','mcp/access']){await go(r);await page.waitForTimeout(40);const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);assert.equal(overflow,false,`Horizontal overflow at ${width} / ${r}`);}if(width===390)await page.screenshot({path:'/tmp/infra-sample-mobile.png',fullPage:true});}
 console.log('Desktop / tablet / mobile layouts: OK');assert.equal(requests.length,0);assert.deepEqual(errors,[]);console.log('PASS: offline demo, no page errors, no HTTP requests');
 }catch(error){console.error('PAGE ERRORS:',errors);console.error('REQUESTS:',requests);await page.screenshot({path:'/tmp/infra-sample-test-failure.png',fullPage:true}).catch(()=>{});throw error;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
