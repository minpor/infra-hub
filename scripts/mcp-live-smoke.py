#!/usr/bin/env python3
"""Exercise the real HTTPS MCP with ephemeral keys/metadata; never replace an Aukni release.

Uses a configured SSH owner alias to issue then revoke test credentials. No secret
is printed or stored. Needs the official Python MCP SDK in the selected Python.
"""
import asyncio,hashlib,json,logging,shlex,subprocess,time,uuid
from pathlib import Path
import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import create_mcp_http_client,streamable_http_client

ROOT=Path(__file__).resolve().parents[1]
COMPOSE='cd /opt/infra-hub/stacks/panel && docker compose -f compose.live.yaml -f compose.repositories.yaml -f compose.deployments.yaml '
URL='https://infra-hub.minpor.ru/mcp'
logging.getLogger('httpx').setLevel(logging.ERROR)
logging.getLogger('httpx2').setLevel(logging.ERROR)
def shell(command,input=None):
 p=subprocess.run(['ssh','infra-hub',command],input=input,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Owner operation failed: '+p.stderr[-500:])
 return p.stdout

def sql(query):return shell(COMPOSE+'exec -T postgres psql -v ON_ERROR_STOP=1 -U infra_hub -d infra_hub -At -c '+shlex.quote(query)).strip()
def admin(plan):
 out=shell(COMPOSE+'exec -T api java -Xmx128m -jar /app/app.jar --spring.profiles.active=admin --spring.main.web-application-type=none --hub.scheduling-enabled=false',json.dumps(plan))
 return json.loads(next(line.split('=',1)[1] for line in out.splitlines() if line.startswith('INFRA_HUB_ADMIN=')))
def dump(model):return model.model_dump(mode='json',by_alias=True)
def structured(result):
 value=dump(result)
 if value.get('isError'):raise RuntimeError('MCP operation failed: '+json.dumps(value.get('structuredContent')))
 return value.get('structuredContent')

async def main():
 issued=[];fixture=None;claim=None;claim_name='mcp-acceptance-'+str(uuid.uuid4())
 evidence={'endpoint':URL,'checks':[]}
 fixture_name='MCP acceptance '+str(uuid.uuid4())[:8]
 try:
  created=admin([{'action':'mcp-token','input':{'name':'MCP acceptance (temporary)','permissions':['READ','RELEASE','CONFIGURE'],'expiresInDays':1}}])[0]
  issued.append(created);secret=created['token']
  assert sql("SELECT token_hash FROM mcp_tokens WHERE id='"+created['id']+"'")==hashlib.sha256(secret.encode()).hexdigest()
  async with create_mcp_http_client(headers={'Authorization':'Bearer '+secret}) as http:
   async with streamable_http_client(URL,http_client=http,terminate_on_close=False) as streams:
    async with ClientSession(*streams,read_timeout_seconds=40) as session:
     init=dump(await session.initialize());evidence['protocol']=init['protocolVersion']
     tools=dump(await session.list_tools())['tools'];evidence['toolNames']=[t['name'] for t in tools]
     assert len(tools)==19
     evidence['checks'].append('SDK initialize and 19 tool schemas')
     catalog=structured(await session.call_tool('hub_catalog',{}))
     assert len(catalog['applications'])>=4
     resources=await session.read_resource('infra-hub://release-guide');assert dump(resources)['contents']
     assert dump(await session.get_prompt('release_workflow'))['messages']
     evidence['checks'].append('live authorized catalog, resource and release prompt')
     p=next(p for p in catalog['pipelines'] if p['ref']=='dev' and next(a for a in catalog['applications'] if a['id']==p['application_id'])['kind']=='COMPOSE')
     before=(p['build_id'],p['deployment_id'])
     ensured=structured(await session.call_tool('hub_ensure_release',{'pipeline_id':p['id'],'commit_sha':p['last_sha'],'request_id':str(uuid.uuid4())}))
     assert (ensured['latestBuild']['id'],ensured['latestDeployment']['id'])==before and ensured['currentMatchesExpected']
     finished=structured(await session.call_tool('hub_cancel_deployment',{'deployment_id':before[1],'request_id':str(uuid.uuid4())}))
     assert finished['status']=='SUCCEEDED'
     evidence['checks'].append('existing exact-SHA release reused; completed deployment not falsely cancelled')
     request={'name':fixture_name,'repository_url':'https://github.com/minpor/aukni','request_id':str(uuid.uuid4())}
     project=structured(await session.call_tool('hub_create_project',request));fixture=project['id']
     assert structured(await session.call_tool('hub_create_project',request))['id']==fixture
     server=next(a['serverId'] for a in catalog['applications'] if a['kind']=='COMPOSE' and a['configuration']['ref']=='dev')
     app_id=str(uuid.uuid4())
     application={'id':app_id,'projectId':fixture,'serverId':server,'name':fixture_name,'hostPort':18090,'containerPort':8080,'healthPath':'/health','environment':{'FIXTURE_SECRET':'not-a-real-secret'}}
     application_request=str(uuid.uuid4())
     made=structured(await session.call_tool('hub_create_application',{'application':application,'request_id':application_request}))
     replay=structured(await session.call_tool('hub_create_application',{'request_id':application_request,'application':dict(reversed(list(application.items())))}))
     assert replay['id']==app_id
     assert 'not-a-real-secret' not in json.dumps(made) and made['environmentKeys']==['FIXTURE_SECRET']
     pipeline_id=str(uuid.uuid4())
     recipe={'id':pipeline_id,'applicationId':app_id,'ref':'fixture','command':'echo fixture','artifactPath':'fixture.jar','enabled':False}
     structured(await session.call_tool('hub_create_pipeline',{'pipeline':recipe,'request_id':str(uuid.uuid4())}))
     configured=structured(await session.call_tool('hub_configure_pipeline',{'pipeline_id':pipeline_id,'command':'echo verified','enabled':False,'request_id':str(uuid.uuid4())}))
     assert configured['command']=='echo verified' and not configured['enabled']
     evidence['checks'].append('idempotent project creation, application and pipeline creation/configuration; environment write-only')
     readonly=admin([{'action':'mcp-token','input':{'name':'MCP scope verification (temporary)','permissions':['READ'],'projectIds':[fixture],'expiresInDays':1}}])[0];issued.append(readonly)
     async with create_mcp_http_client(headers={'Authorization':'Bearer '+readonly['token']}) as scoped_http:
      async with streamable_http_client(URL,http_client=scoped_http,terminate_on_close=False) as scoped_streams:
       async with ClientSession(*scoped_streams,read_timeout_seconds=30) as scoped:
        await scoped.initialize()
        assert len(dump(await scoped.list_tools())['tools'])==7
        visible=structured(await scoped.call_tool('hub_catalog',{}));assert [p['id'] for p in visible['projects']]==[fixture]
        forbidden=dump(await scoped.call_tool('hub_get_build',{'build_id':before[0]}));assert forbidden['isError'] and forbidden['structuredContent']['error']['code']=='NOT_FOUND'
        denied=dump(await scoped.call_tool('hub_cancel_build',{'build_id':before[0],'request_id':str(uuid.uuid4())}));assert denied['isError'] and denied['structuredContent']['error']['code']=='FORBIDDEN'
     evidence['checks'].append('read-only discovery and cross-project access/write denials')
     # Make only this temporary project use the native built-in Java fixture.
     sql("UPDATE projects SET repository_url=NULL WHERE id='"+fixture+"'")
     claim=subprocess.Popen(['ssh','infra-hub',COMPOSE+'exec -T -e PGAPPNAME='+claim_name+' postgres psql -U infra_hub -d infra_hub -At -c '+shlex.quote('SELECT pg_advisory_lock(4821901); SELECT pg_sleep(120);')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
     for _ in range(40):
      if sql("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.application_name='"+claim_name+"' AND l.locktype='advisory' AND l.granted")=='1':break
      await asyncio.sleep(.1)
     else:raise RuntimeError('Fixture claim lock unavailable')
     run=structured(await session.call_tool('hub_run_build',{'project_id':fixture,'request_id':str(uuid.uuid4())}));assert run['status']=='QUEUED'
     cancelled=structured(await session.call_tool('hub_cancel_build',{'build_id':run['id'],'request_id':str(uuid.uuid4())}));assert cancelled['status']=='CANCELLED'
     status=structured(await session.call_tool('hub_get_build',{'build_id':run['id']}));assert status['build']['status']=='CANCELLED'
     assert structured(await session.call_tool('hub_build_logs',{'build_id':run['id'],'after':0}))['entries']==[]
     evidence['checks'].append('real queued build creation, durable cancellation, status and cursor logs')
     evidence['runner']=structured(await session.call_tool('hub_runner_settings',{}))
  admin([{'action':'mcp-revoke','input':{'id':v['id']}} for v in issued])
  async with httpx2.AsyncClient() as http:
   for v in issued:
    response=await http.post(URL,headers={'Authorization':'Bearer '+v['token'],'Accept':'application/json, text/event-stream'},json={'jsonrpc':'2.0','id':1,'method':'ping'})
    assert response.status_code==401
  evidence['checks'].append('revocation immediately rejects both credentials')
  evidence['result']='PASS'
 finally:
  if claim:
   sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='"+claim_name+"'");claim.wait(timeout=10)
  if issued:
   admin([{'action':'mcp-revoke','input':{'id':v['id']}} for v in issued])
   ids=','.join("'"+v['id']+"'" for v in issued)
   sql('DELETE FROM mcp_requests WHERE token_id IN ('+ids+'); DELETE FROM mcp_tokens WHERE id IN ('+ids+')')
  if fixture:
   sql("DELETE FROM pipelines WHERE application_id IN (SELECT id FROM applications WHERE project_id='"+fixture+"'); DELETE FROM applications WHERE project_id='"+fixture+"'; DELETE FROM build_logs WHERE build_id IN (SELECT id FROM builds WHERE project_id='"+fixture+"'); DELETE FROM build_steps WHERE build_id IN (SELECT id FROM builds WHERE project_id='"+fixture+"'); DELETE FROM builds WHERE project_id='"+fixture+"'; DELETE FROM projects WHERE id='"+fixture+"' AND name='"+fixture_name+"'")
 (ROOT/'.local/checks/mcp-live.json').write_text(json.dumps(evidence,indent=2))
 print(json.dumps(evidence,indent=2))

asyncio.run(main())
