$ProgressPreference = "SilentlyContinue"
$phone = "+919494949494"
Set-Content docker\r.json ("{""mobileNumber"":""" + $phone + """}") -NoNewline -Encoding ascii
curl.exe -s -o NUL -X POST -H "Content-Type: application/json" --data "@docker/r.json" http://localhost:5173/api/auth/register/otp
Start-Sleep -Seconds 1
$otp = ([regex]"code is (\d{6})").Match((docker logs homefix-core-auth-service-1 2>&1 | Select-String "DEV SMS" | Select-Object -Last 1).ToString()).Groups[1].Value
Set-Content docker\v.json ("{""mobileNumber"":""" + $phone + """,""otp"":""" + $otp + """}") -NoNewline -Encoding ascii
$verify = curl.exe -s -X POST -H "Content-Type: application/json" --data "@docker/v.json" http://localhost:5173/api/auth/register/verify
$token = ([regex]'"accessToken"\s*:\s*"([^"]+)"').Match($verify).Groups[1].Value
Set-Content docker\est.json '{"subcategoryId":"56920d1a-f927-497a-afd7-1736c78eb77b","emergency":false}' -NoNewline -Encoding ascii
curl.exe -s -o NUL -w "%{http_code}" -H ("Authorization: Bearer " + $token) -H "Content-Type: application/json" --data "@docker/est.json" http://localhost:8086/pricing/estimate | Out-File docker\st.txt -Encoding ascii
Remove-Item docker\r.json,docker\v.json,docker\est.json -ErrorAction SilentlyContinue
