/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
/*
handles the /admin api endpoint

handler return "GOOD" when GET request is sent to /admin

example curl
	$ curl -D- http://localhost:1212/admin
	HTTP/1.1 200 OK
	Content-Type: text/plain
	Date: Fri, 02 Jun 2017 15:16:43 GMT
	Content-Length: 6

	GOOD
*/

package main

import (
	"fmt"
	"net/http"
)

// admin http endpoint used for healthchecking
func admin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain")
	fmt.Fprint(w, "GOOD\r\n")
}
