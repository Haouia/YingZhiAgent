import {createApp} from 'vue'
import {createPinia} from 'pinia'
import {createRouter,createWebHashHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './style.css'
const router=createRouter({history:createWebHashHistory(),routes:[{path:'/:page?',component:{template:'<span />'}}]})
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')

