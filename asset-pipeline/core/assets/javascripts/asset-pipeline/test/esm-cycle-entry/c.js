import { token, a } from './a.js'
console.log('a evaluated', globalThis.aEvaluations, 'time(s); same token through the cycle:', a() === token)
