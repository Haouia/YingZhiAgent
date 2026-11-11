export async function api(path, options={}) {
  const headers={...options.headers}
  const token=sessionStorage.getItem('camp-token')
  if(token)headers.Authorization='Bearer '+token
  if(options.body && !(options.body instanceof FormData)) {
    headers['Content-Type']='application/json'
    options.body=JSON.stringify(options.body)
  }
  const response=await fetch('/api'+path,{...options,headers})
  const data=await response.json().catch(()=>({message:response.status===429?'请求过于频繁，请稍后重试':'服务器响应无效'}))
  if(sessionStorage.getItem('camp-token')!==token)throw new DOMException('登录身份已变化，已丢弃旧请求','AbortError')
  if(!response.ok) {
    if(response.status===401){sessionStorage.removeItem('camp-token');window.dispatchEvent(new Event('camp-unauthorized'))}
    throw new Error(data.message||'请求失败，请稍后重试')
  }
  return data
}

