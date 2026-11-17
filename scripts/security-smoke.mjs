// 검증 Compose 전용. 실제 자격증명/응답 토큰을 로그에 출력하지 않는다.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
const base='http://127.0.0.1:18080';
const project='orderpage-readiness';
const label=execFileSync('docker',['inspect',project+'-app-1','--format','{{index .Config.Labels "com.docker.compose.project"}}'],{encoding:'utf8'}).trim();
assert.equal(label,project);
assert.match(execFileSync('docker',['port',project+'-app-1','8080/tcp'],{encoding:'utf8'}),/^127\.0\.0\.1:18080/);
const results=[];
async function request(route,method,body,status,token){
 const r=await fetch(base+route,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body&&JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 assert.equal(r.status,status,method+' '+route+' status');
 const text=await r.text();
 if(body?.password&&status===400)assert.ok(!text.includes(body.password),'input must not be echoed');
 // 기존 JWT filter의 401 body는 JSON 헤더를 가진 고정 문자열이다.
 if (status===401 && text==='token error') return null;
 return text?JSON.parse(text):null;
}
async function check(name,action){await action();results.push(name);console.log('PASS '+name);}
const email='security-'+randomUUID()+'@example.test';
const password='가'.repeat(24); // UTF-8 72바이트 경계
try {
 await check('72-byte signup and existing response contract',async()=>{
   const r=await request('/member/create','POST',{name:'security-smoke',email,password},201);
   assert.equal(r.status_code,201);assert.equal(typeof r.result,'number');
 });
 let login;
 await check('login and Redis-backed refresh',async()=>{
   login=(await request('/doLogin','POST',{email,password},200)).result;
   const refreshed=await request('/refresh-token','POST',{refreshToken:login.refreshToken},200);
   assert.equal(typeof refreshed.result.token,'string');
   await request('/member/myInfo','GET',undefined,200,refreshed.result.token);
 });
 await check('oversized signup/login/reset rejected',async()=>{
   await request('/member/create','POST',{email:'rejected-'+email,password:password+'x'},400);
   await request('/doLogin','POST',{email,password:password+'x'},400);
   await request('/member/reset-password','PATCH',{email,asIsPassword:password+'x',toBePassword:'new-password'},400);
   await request('/member/reset-password','PATCH',{email,asIsPassword:password,toBePassword:password+'x'},400);
 });
 await check('refresh token cannot authenticate API and access cannot refresh',async()=>{
   await request('/member/myInfo','GET',undefined,401,login.refreshToken);
   await request('/refresh-token','POST',{refreshToken:login.token},401);
 });
 await check('password change rejects old and accepts new password',async()=>{
   await request('/member/reset-password','PATCH',{email,asIsPassword:password,toBePassword:'new-password'},200);
   await request('/doLogin','POST',{email,password},400);
   await request('/doLogin','POST',{email,password:'new-password'},200);
 });
 await check('Prometheus exports HTTP histogram and JVM metrics',async()=>{
   const r=await fetch(base+'/actuator/prometheus',{signal:AbortSignal.timeout(15000)});
   assert.equal(r.status,200);const text=await r.text();
   assert.match(text,/^http_server_requests_seconds_bucket\{/m);
   assert.match(text,/^jvm_memory_used_bytes\{/m);
 });
 console.log(JSON.stringify({checks:results.length,status:'PASS',scope:'local isolated Compose; no AWS or TLS proof'}));
} catch(error){console.error('Security smoke failed: '+error.message);process.exitCode=1;}
