#!/usr/bin/env python3
"""Read-only console smoke check. Credentials and response payloads are never reported."""
import argparse
import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--auth-file', required=True, help='Private JSON file with username/password')
    parser.add_argument('--route', help='Existing route name for the balance-plugin read check')
    parser.add_argument('--output', default='openapi-smoke-result.json')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.password:
        parser.error('base-url must be an HTTP(S) URL without credentials')
    results = {'base_url': base, 'checks': [], 'scope': 'read-only; login/logout use current console session'}
    cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar), NoRedirect())
    anonymous = urllib.request.build_opener(NoRedirect())

    def request(path, expected=200, payload=None, authenticated=True, json_response=False):
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(base + path, data=data,
                                     headers={'Content-Type': 'application/json'} if data else {})
        try:
            response = (opener if authenticated else anonymous).open(req, timeout=30)
        except urllib.error.HTTPError as exc:
            response = exc
        except (urllib.error.URLError, TimeoutError):
            results['checks'].append({'path': path, 'result': 'connection-failed'})
            raise RuntimeError('Connection failed') from None
        with response:
            status = response.code
            body = response.read()
            results['checks'].append({'path': path, 'authenticated': authenticated,
                                      'status': status, 'expected': expected})
            if status != expected:
                raise RuntimeError('Unexpected HTTP status')
            if json_response:
                value = json.loads(body)
                if value.get('success') is False:
                    raise RuntimeError('Console returned success=false')
                return value
            return body

    try:
        for path in ('/swagger-ui/index.html', '/v3/api-docs/Higress', '/v3/api-docs.yaml/Higress',
                     '/v1/service-sources', '/v1/ai/providers', '/v1/ai/routes', '/v1/api-keys/group/list'):
            request(path, expected=401, authenticated=False)
        credentials = json.loads(Path(args.auth_file).read_text())
        request('/session/login', expected=201, payload={'username': credentials['username'],
                                          'password': credentials['password']}, json_response=True)
        del credentials
        if not list(cookie_jar):
            raise RuntimeError('Login did not issue a session cookie')
        request('/swagger-ui.html', expected=302)
        html = request('/swagger-ui/index.html')
        if b'swagger-ui' not in html:
            raise RuntimeError('Swagger UI response is not the expected HTML')
        request('/swagger-ui/swagger-ui-bundle.js')
        request('/v3/api-docs/swagger-config', json_response=True)
        spec = request('/v3/api-docs/Higress', json_response=True)
        required = {'/v1/service-sources': ('get', 'post'), '/v1/ai/providers': ('get',),
                    '/v1/ai/routes': ('get', 'post'), '/v1/ai/routes/{name}': ('get', 'put', 'delete'),
                    '/v1/api-keys/group/list': ('get',), '/v1/routes': ('get',),
                    '/v1/wasm-plugins/{name}': ('get',),
                    '/v1/routes/{routeName}/plugin-instances/{name}': ('get', 'put', 'delete')}
        for path, methods in required.items():
            for method in methods:
                operation = spec.get('paths', {}).get(path, {}).get(method)
                if operation is None or not operation.get('security'):
                    raise RuntimeError('Required authenticated operation missing from OpenAPI')
        results['openapi'] = {'version': spec.get('openapi'), 'info': spec.get('info'),
                              'path_count': len(spec.get('paths', {}))}
        for path in ('/v1/service-sources', '/v1/ai/providers', '/v1/ai/routes', '/v1/routes',
                     '/v1/api-keys/group/list'):
            request(path, json_response=True)
        plugin = request('/v1/wasm-plugins/user-balance-check', json_response=True)
        if not plugin.get('data'):
            raise RuntimeError('user-balance-check plugin is not installed')
        if args.route:
            route = urllib.parse.quote(args.route, safe='')
            instance = request('/v1/routes/' + route + '/plugin-instances/user-balance-check', json_response=True)
            results['balance_plugin_enabled'] = instance.get('data', {}).get('enabled')
        else:
            results['route_plugin_check'] = 'not-run: supply --route with an existing route'
        request('/session/logout', json_response=True)
        request('/v3/api-docs/Higress', expected=401)
        results['result'] = 'passed'
    except Exception as exc:
        # Do not leak credentials, URLs from exceptions, or upstream response bodies.
        results['result'] = 'failed'
        results['error_type'] = type(exc).__name__
        if type(exc) is RuntimeError:
            results['error'] = str(exc)
    Path(args.output).write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': results['result'], 'check_count': len(results['checks'])}))
    return 0 if results['result'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
