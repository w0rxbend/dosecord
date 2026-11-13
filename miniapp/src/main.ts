import './styles/tokens.css';
import './styles/base.css';
import { start } from './app/start';

const target = document.getElementById('app');
if (target) void start(target);
