import {defineStore} from 'pinia'
import {ref} from 'vue'
import {api} from '../api'
export const useSession=defineStore('session',()=>{
 const user=ref(null)
 async function load(){if(sessionStorage.getItem('camp-token'))user.value=await api('/auth/me')}
 async function login(username,password){const r=await api('/auth/login',{method:'POST',body:{username,password}});sessionStorage.setItem('camp-token',r.token);await load()}
 function logout(){sessionStorage.removeItem('camp-token');user.value=null}
 return {user,load,login,logout}
})

