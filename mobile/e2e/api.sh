# Sourced by e2e/run. A bearer session for a synthetic member, through the real app login and the synthetic OIDC page.
api_login() {
  subject=$1
  jar=$(mktemp)
  verifier=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n')
  state=$(head -c 16 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
  authorize=$(curl -fsS -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' \
    "http://app:3000/auth/login?app=android&redirect_uri=com.epaflix.fytorium:/oauth2redirect&code_challenge=$challenge&code_challenge_method=S256&state=$state")
  callback=$(curl -fsS -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' --data-urlencode "username=$subject" --data-urlencode 'claims=' "$authorize")
  redirect=$(curl -fsS -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' "$callback")
  rm -f "$jar"
  code=$(printf '%s' "$redirect" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  [ -n "$code" ] || { echo "api_login: no code for $subject" >&2; return 1; }
  curl -fsS -H 'Content-Type: application/json' \
    -d "{\"grant_type\":\"authorization_code\",\"code\":\"$code\",\"code_verifier\":\"$verifier\",\"device_name\":\"e2e $subject\"}" \
    http://app:3000/api/app/token | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

# api <token> <method> <path> [json body]
api() {
  if [ $# -ge 4 ]; then
    curl -fsS -X "$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H 'X-App-Version: 99.0.0' -d "$4" "http://app:3000$3"
  else
    curl -fsS -X "$2" -H "Authorization: Bearer $1" -H 'X-App-Version: 99.0.0' "http://app:3000$3"
  fi
}

json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
