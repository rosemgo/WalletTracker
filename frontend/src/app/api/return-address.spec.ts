import { originOf, reachableFromEveryDevice, returnAddressFor } from './return-address';

/** Gli stessi casi di EnableBankingPropertiesTest (backend): le due regole devono dire la stessa cosa. */
describe('returnAddressFor', () => {
  const NG_SERVE = 'https://localhost:8443/callback';
  const TAILSCALE = 'https://pc-casa.tail0000.ts.net/callback';

  it('from the page with the same address the bank comes back to it', () => {
    const urls = [NG_SERVE, TAILSCALE];
    expect(returnAddressFor('https://localhost:8443', urls)).toEqual({
      url: NG_SERVE,
      samePage: true,
    });
    expect(returnAddressFor('https://pc-casa.tail0000.ts.net', urls)).toEqual({
      url: TAILSCALE,
      samePage: true,
    });
    expect(returnAddressFor('HTTPS://PC-CASA.tail0000.ts.net:443', urls)?.samePage).toBe(true);
  });

  it('from the server in Docker the return is the Tailscale address', () => {
    const urls = [NG_SERVE, TAILSCALE];
    expect(returnAddressFor('http://localhost:8000', urls)).toEqual({
      url: TAILSCALE,
      samePage: false,
    });
    expect(returnAddressFor('http://127.0.0.1:8000', urls)).toEqual({
      url: TAILSCALE,
      samePage: false,
    });
  });

  it('without an address that answers there is none', () => {
    expect(returnAddressFor('http://localhost:8000', [NG_SERVE])).toBeNull();
    expect(returnAddressFor('http://localhost:8000', ['http://pc-casa.lan/callback'])).toBeNull();
    expect(returnAddressFor('http://localhost:8000', [])).toBeNull();
  });

  it('an origin is scheme, name and port', () => {
    expect(originOf('https://Localhost:8443/callback')).toBe('https://localhost:8443');
    expect(originOf('https://pc.ts.net/callback')).toBe('https://pc.ts.net:443');
    expect(originOf('http://localhost')).toBe('http://localhost:80');
    expect(originOf('non è un indirizzo')).toBeNull();
    expect(reachableFromEveryDevice(TAILSCALE)).toBe(true);
    expect(reachableFromEveryDevice(NG_SERVE)).toBe(false);
  });
});
