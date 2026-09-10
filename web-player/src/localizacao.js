import './styles/main.css';
import { mountCosmos } from './ui/cosmos.js';

/**
 * The page that explains where the numbers on the provenance page come from.
 *
 * All prose, no rendering: everything here is static markup in localizacao.html. This module
 * exists because the stylesheet is loaded through JS on every page rather than with a <link>,
 * and because a page without the starfield would look like a different site.
 */
mountCosmos();
