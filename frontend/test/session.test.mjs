import test, {beforeEach} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {ref, computed, reactive, watch, nextTick} from 'vue'
import {api} from '../src/api.js'

let storage, notices
const response=(data,status=200)=>({ok:status>=200&&status<300,status,json:async()=>data})
beforeEach(()=>{
  storage=new Map([['camp-token','ops-token']])
  Object.defineProperty(globalThis,'sessionStorage',{configurable:true,value:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}})
  notices=[]
  globalThis.window={dispatchEvent:event=>notices.push(event.type)}
  globalThis.fetch=async()=>response([])
})
function app(){
  const session=reactive({user:{id:'ops',role:'OPS',name:'运营'}})
  const route=reactive({params:{page:'knowledge'}})
  const router={push(path){route.params.page=path.slice(1)},replace(path){this.push(path)}}
  const source=fs.readFileSync(new URL('../src/App.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
  const context={ref,computed,watch,onMounted(){},useRoute:()=>route,useRouter:()=>router,useSession:()=>session,api,crypto:globalThis.crypto,ElMessage:{error:m=>notices.push(m),success(){}}}
  for(const name of ['ChatDotRound','Collection','Service','Location','DataLine','SwitchButton','Promotion','Plus','Document','ArrowRight'])context[name]={}
  return vm.runInNewContext(`(()=>{${source};return {session,docDetail,docOpen,citation,citationOpen,metrics,documents,file,uploadOpen,viewDocument,messages,conversationId,selectConversation,newConversation,navigate};})()`,context)
}
test('switching accounts clears internal data and dialogs synchronously',async()=>{
  globalThis.fetch=async()=>response({id:'private-doc',versions:[{extracted_text:'INTERNAL'}]})
  const state=app()
  await state.viewDocument({id:'private-doc'})
  state.citation.value={content:'INTERNAL'};state.citationOpen.value=true;state.metrics.value={private:true};state.uploadOpen.value=true
  storage.set('camp-token','user-token');state.session.user={id:'user',role:'USER'}
  assert.equal(state.docDetail.value,null)
  assert.equal(state.docOpen.value,false)
  assert.equal(state.citation.value,null)
  assert.equal(state.citationOpen.value,false)
  assert.equal(state.metrics.value,null)
  assert.equal(state.uploadOpen.value,false)
  await nextTick()
})
test('a response from a previous login is discarded',async()=>{
  let resolve
  globalThis.fetch=()=>new Promise(r=>resolve=r)
  const pending=api('/ops/documents')
  storage.set('camp-token','new-token')
  resolve(response([{content:'INTERNAL'}]))
  await assert.rejects(pending,{name:'AbortError'})
})
test('a stale 401 cannot log out the new account',async()=>{
  let resolve
  globalThis.fetch=()=>new Promise(r=>resolve=r)
  const pending=api('/auth/me')
  storage.set('camp-token','new-token')
  resolve(response({message:'expired'},401))
  await assert.rejects(pending,{name:'AbortError'})
  assert.equal(storage.get('camp-token'),'new-token')
  assert.deepEqual(notices,[])
})
test('old conversation replies cannot populate the next account',async()=>{
  let resolve
  globalThis.fetch=()=>new Promise(r=>resolve=r)
  const state=app()
  const pending=state.selectConversation('previous-user-conversation')
  storage.set('camp-token','user2-token');state.session.user={id:'user2',role:'USER'}
  await nextTick()
  resolve(response([{content:'PREVIOUS-USER'}]))
  await pending
  assert.equal(state.messages.value.length,0)
  assert.equal(state.conversationId.value,null)
  assert.deepEqual(notices,[])
})
test('new conversation ignores an older conversation load',async()=>{
  let resolve
  globalThis.fetch=()=>new Promise(r=>resolve=r)
  const state=app()
  const pending=state.selectConversation('old')
  state.newConversation()
  resolve(response([{content:'OLD'}]))
  await pending
  assert.equal(state.messages.value.length,0)
  assert.equal(state.conversationId.value,null)
})
test('refreshing the current route fetches its data',async()=>{
  const calls=[]
  globalThis.fetch=async path=>{calls.push(path);return response([{id:'latest'}])}
  const state=app()
  await state.navigate('knowledge')
  await nextTick()
  assert.deepEqual(calls,['/api/ops/documents'])
  assert.equal(state.documents.value[0].id,'latest')
})
test('non-JSON rate limit responses have a useful error',async()=>{
  globalThis.fetch=async()=>({ok:false,status:429,json:async()=>{throw new Error('HTML')}})
  await assert.rejects(api('/auth/login'),/请求过于频繁/)
})
