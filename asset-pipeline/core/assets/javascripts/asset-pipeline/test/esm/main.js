import { twice } from './lib/math.js'
import {
	greet,
} from "./greet.js"
import './side-effect.js'
export * from './lib/math.js'
import { shared } from '../esm-shared/shared.js?v=1'
import bare from 'some-package'
import remote from 'https://example.com/remote.js'
import missing from './missing.js'
loader.import('./greet.js')

export function run() {
	return import('./lazy.js').then(lazy => greet(twice(lazy.value + shared)))
}
