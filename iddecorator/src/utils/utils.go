/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package utils

import (
	"io/ioutil"
	"log"
	"gopkg.in/yaml.v2"
)


type ConfigMap struct {
	Configs map[string]Config `yaml:"configs"`
}


type Config struct {
	Paths []string `yaml:"paths"`
	NeverOverwriteAccounts []string `yaml:"neverOverwriteAccounts"`
}

func ParseYamlToConfigMap(file string) (configMap ConfigMap) {
	if data, err := ioutil.ReadFile(file); err == nil {
		if e := yaml.UnmarshalStrict(data, &configMap); e != nil {
			log.Fatalf("Cannot parse given file %s. Error: %v", file, e)
		}
	} else {
		log.Fatalf("Cannot read given file %s Error: %v", file, err)
	}
	return configMap
}

func ParseYaml(file string) (output interface{}) {
	if data, err := ioutil.ReadFile(file); err == nil {
		if e := yaml.UnmarshalStrict(data, &output); e != nil {
			log.Fatalf("Cannot parse given file %s. Error: %v", file, e)
		}
	} else {
		log.Fatalf("Cannot read given file %s Error: %v", file, err)
	}
	return output
}
