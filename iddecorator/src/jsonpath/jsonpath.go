/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package jsonpath

import (
	"bytes"
	"fmt"
	"io"
	"k8s.io/client-go/third_party/forked/golang/template"
	k8sJsonPath "k8s.io/client-go/util/jsonpath"
	"path"
	"reflect"
	"regexp"
	"strings"
	"golang.linkedin.com/iddecorator/iddecorator/logger"
)

const Root = "/"
var nonAlphaNum = regexp.MustCompile(`\W`)

type JSONPath struct {
	name             string
	Paths            []string
	pathToFind       string
	children         []string
	parser           *k8sJsonPath.Parser
	stack            [][]reflect.Value // push and pop values in different scopes
	cur              []reflect.Value   // current scope values
	beginRange       int
	inRange          int
	endRange         int
	logger           logger.LoggerI
	allowMissingKeys bool
}

// New creates a new JSONPath with the given name.
func New(name string) *JSONPath {
	return &JSONPath{
		name:       name,
		beginRange: 0,
		inRange:    0,
		endRange:   0,
	}
}

func (j *JSONPath) SetLogger(logger logger.LoggerI) *JSONPath {
	j.logger = logger
	return j
}

// AllowMissingKeys allows a caller to specify whether they want an error if a field or map key
// cannot be located, or simply an empty result. The receiver is returned for chaining.
func (j *JSONPath) AllowMissingKeys(allow bool) *JSONPath {
	j.allowMissingKeys = allow
	return j
}

// Parse parses the given template and returns an error.
func (j *JSONPath) Parse(text string) error {
	var err error
	j.parser, err = k8sJsonPath.Parse(j.name, text)
	return err
}

// Execute bounds data into template and writes the result.
func (j *JSONPath) Execute(wr io.Writer, data interface{}) error {
	fullResults, err := j.FindResults(data)
	if err != nil {
		return err
	}
	for ix := range fullResults {
		if err := j.PrintResults(wr, fullResults[ix]); err != nil {
			return err
		}
	}
	return nil
}

// Finds the paths by creating a string array from the FieldNodes
func (j *JSONPath) FindPaths() [] string{
	j.logger.Debugf("paths %v", j.Paths)
	return j.Paths
}

func (j *JSONPath) fieldToChildren() {
	var children []string
	for _, node := range j.parser.Root.Nodes {
		switch node := node.(type) {
		case *k8sJsonPath.ListNode:
			for _, n := range node.Nodes {
				switch n := n.(type) {
				case *k8sJsonPath.FieldNode:
					children = append(children, fmt.Sprintf("%v", n.Value))
				}
			}
		}
	}
	j.pathToFind = path.Join(children...)
	j.logger.Debugf("pathToFind %s", j.pathToFind)
	j.children = children
}

// Finds results by walking down a json graph
func (j *JSONPath) FindResults(data interface{}) ([][]reflect.Value, error) {
	if j.parser == nil {
		return nil, fmt.Errorf("%s is an incomplete jsonpath template", j.name)
	}
	j.fieldToChildren()
	j.cur = []reflect.Value{reflect.ValueOf(data)}

	nodes := j.parser.Root.Nodes
	fullResult := [][]reflect.Value{}
	for i := 0; i < len(nodes); i++ {
		node := nodes[i]
		results, err := j.walk(j.cur, node)
		if err != nil {
			return nil, err
		}

		// encounter an end node, break the current block
		if j.endRange > 0 && j.endRange <= j.inRange {
			j.endRange--
			break
		}
		fullResult = append(fullResult, results)
	}
	return fullResult, nil
}

// PrintResults writes the results into writer
func (j *JSONPath) PrintResults(wr io.Writer, results []reflect.Value) error {
	for i, r := range results {
		text, err := j.evalToText(r)
		if err != nil {
			return err
		}
		if i != len(results)-1 {
			text = append(text, ' ')
		}
		if _, err = wr.Write(text); err != nil {
			return err
		}
	}
	return nil
}

// walk visits tree rooted at the given node in DFS order
func (j *JSONPath) walk(value []reflect.Value, node k8sJsonPath.Node) ([]reflect.Value, error) {
	switch node := node.(type) {
	case *k8sJsonPath.ListNode:
		return j.evalList(value, node)
	case *k8sJsonPath.TextNode:
		return []reflect.Value{reflect.ValueOf(node.Text)}, nil
	case *k8sJsonPath.FieldNode:
		return j.evalField(value, node)
	case *k8sJsonPath.FilterNode:
		return j.evalFilter(value, node)
	case *k8sJsonPath.IntNode:
		return j.evalInt(value, node)
	case *k8sJsonPath.BoolNode:
		return j.evalBool(value, node)
	case *k8sJsonPath.FloatNode:
		return j.evalFloat(value, node)
	case *k8sJsonPath.RecursiveNode:
		// for now parent is blank. If we want to support expressions like {field1..field2}
		// then we must expand the meaning of parent to whatever's before
		//first child of template.metadata.annotations would be template
		return j.evalRecursiveLoop(value, node)
	default:
		return value, fmt.Errorf("unexpected Node %v", node)
	}
}

// evalInt evaluates IntNode
func (j *JSONPath) evalInt(input []reflect.Value, node *k8sJsonPath.IntNode) ([]reflect.Value, error) {
	result := make([]reflect.Value, len(input))
	for i := range input {
		result[i] = reflect.ValueOf(node.Value)
	}
	return result, nil
}

// evalFloat evaluates FloatNode
func (j *JSONPath) evalFloat(input []reflect.Value, node *k8sJsonPath.FloatNode) ([]reflect.Value, error) {
	result := make([]reflect.Value, len(input))
	for i := range input {
		result[i] = reflect.ValueOf(node.Value)
	}
	return result, nil
}

// evalBool evaluates BoolNode
func (j *JSONPath) evalBool(input []reflect.Value, node *k8sJsonPath.BoolNode) ([]reflect.Value, error) {
	result := make([]reflect.Value, len(input))
	for i := range input {
		result[i] = reflect.ValueOf(node.Value)
	}
	return result, nil
}

// evalList evaluates ListNode
func (j *JSONPath) evalList(value []reflect.Value, node *k8sJsonPath.ListNode) ([]reflect.Value, error) {
	var err error
	curValue := value
	for _, node := range node.Nodes {
		curValue, err = j.walk(curValue, node)
		if err != nil {
			return curValue, err
		}
	}
	return curValue, nil
}

func (j *JSONPath) findFieldInValue(value *reflect.Value, node *k8sJsonPath.FieldNode) (reflect.Value, error) {
	t := value.Type()
	var inlineValue *reflect.Value
	for ix := 0; ix < t.NumField(); ix++ {
		f := t.Field(ix)
		jsonTag := f.Tag.Get("json")
		parts := strings.Split(jsonTag, ",")
		if len(parts) == 0 {
			continue
		}
		if parts[0] == node.Value {
			return value.Field(ix), nil
		}
		if len(parts[0]) == 0 {
			val := value.Field(ix)
			inlineValue = &val
		}
	}
	if inlineValue != nil {
		if inlineValue.Kind() == reflect.Struct {
			// handle 'inline'

			match, err := j.findFieldInValue(inlineValue, node)

			if err != nil {
				return reflect.Value{}, err
			}
			if match.IsValid() {
				return match, nil
			}
		}
	}
	return value.FieldByName(node.Value), nil
}

// evalField evaluates field of struct or key of map.
func (j *JSONPath) evalField(input []reflect.Value, node *k8sJsonPath.FieldNode) ([]reflect.Value, error) {
	results := []reflect.Value{}
	// If there's no input, there's no output
	if len(input) == 0 {
		return results, nil
	}
	for _, value := range input {
		var result reflect.Value
		value, isNil := template.Indirect(value)
		if isNil {
			continue
		}
		//j.logger.Debugf("value %v", value)
		if value.Kind() == reflect.Struct {
			var err error
			if result, err = j.findFieldInValue(&value, node); err != nil {
				return nil, err
			}
		} else if value.Kind() == reflect.Map {
			mapKeyType := value.Type().Key()
			nodeValue := reflect.ValueOf(node.Value)
			if !nodeValue.Type().ConvertibleTo(mapKeyType) {
				return results, fmt.Errorf("%s is not convertible to %s", nodeValue, mapKeyType)
			}
			key := nodeValue.Convert(mapKeyType)

			result = value.MapIndex(key)

		}
		if result.IsValid() {
			results = append(results, result)
		}
	}
	if len(results) == 0 {
		if j.allowMissingKeys {
			return results, nil
		}
		return results, fmt.Errorf("%s is not found", node.Value)
	}
	return results, nil
}

func (j *JSONPath) evalRecursiveLoop(input []reflect.Value, node *k8sJsonPath.RecursiveNode) ([]reflect.Value, error) {
	result := []reflect.Value{}
	//at each recursive level, expand the input using reflection
	//if it's empty, stop
	//if not,recurse down with the expanded list
	for _, value := range input {
		result = append(result, j.evalRecursive([]string{Root}, value, node)...)
	}
	return result, nil
}

func (j *JSONPath) pathMatch(pathSoFar []string) (match bool) {
	if len(pathSoFar) < len(j.children) {
		return false
	}
	k := len(pathSoFar) - 1
	for i := len(j.children) - 1; i >= 0; i-- {
		if j.children[i] != pathSoFar[k] {
			return false
		}
		k--
	}
	return true
}
// evalRecursive visits the given value recursively and pushes all of them to result
func (j *JSONPath) evalRecursive(pathSoFar []string, input reflect.Value, node *k8sJsonPath.RecursiveNode) ([]reflect.Value) {
	result := []reflect.Value{}
	if j.pathMatch(pathSoFar) {
		j.Paths = append(j.Paths, path.Join(pathSoFar...))
		return nil
	}
	//at each recursive level, expand the input using reflection
	//if it's empty, stop
	//if not,recurse down with the expanded list
	results := []reflect.Value{}
	value, isNil := template.Indirect(input)
	if isNil {
		return nil
	}
	kind := value.Kind()
	if kind == reflect.Struct {
		for i := 0; i < value.NumField(); i++ {
			childVal := value.Field(i)
			results = append(results, childVal)
			childKey := fmt.Sprintf("%v", value.Type().Field(i).Name)
			pathSoFar = append(pathSoFar, childKey)
			result = append(result, j.evalRecursive(pathSoFar, childVal, node)...)
			pathSoFar = pathSoFar[:len(pathSoFar) - 1]

		}
	} else if kind == reflect.Map {
		for _, key := range value.MapKeys() {

			childVal := value.MapIndex(key)
			results = append(results, childVal)

			childKey := fmt.Sprintf("%v", key)
			pathSoFar = append(pathSoFar, childKey)
			result = append(result, j.evalRecursive(pathSoFar, childVal, node)...)
			pathSoFar = pathSoFar[:len(pathSoFar) - 1]
		}
	} else if kind == reflect.Array || kind == reflect.Slice || kind == reflect.String {
		for i := 0; i < value.Len(); i++ {
			childVal := value.Index(i)
			results = append(results, childVal)
			childKey := string(i)
			pathSoFar = append(pathSoFar, childKey)
			result = append(result, j.evalRecursive(pathSoFar, childVal, node)...)
			pathSoFar = pathSoFar[:len(pathSoFar) - 1]
		}
	}
	return result
}

func (j *JSONPath) getKeys(value reflect.Value) (childKeys []string){
	value, isNil := template.Indirect(value)
	if isNil {
		j.logger.Errorf("Value '%v' has 0 value", value)
		return childKeys
	}
	kind := value.Kind()
	if kind == reflect.Struct {
		for i := 0; i < value.NumField(); i++ {
			childKey := fmt.Sprintf("%v", value.Type().Field(i).Name)
			childKeys = append(childKeys, childKey)
		}
	} else if kind == reflect.Map {
		for _, key := range value.MapKeys() {
			childKey := fmt.Sprintf("%v", key)
			childKeys = append(childKeys, childKey)
		}
	}

	return childKeys
}

// evalFilter filters array according to FilterNode
func (j *JSONPath) evalFilter(input []reflect.Value, node *k8sJsonPath.FilterNode) ([]reflect.Value, error) {
	results := []reflect.Value{}
	for _, value := range input {
		value, _ = template.Indirect(value)

		if value.Kind() != reflect.Array && value.Kind() != reflect.Slice {
			return input, fmt.Errorf("%v is not array or slice and cannot be filtered", value)
		}
		for i := 0; i < value.Len(); i++ {
			temp := []reflect.Value{value.Index(i)}
			lefts, err := j.evalList(temp, node.Left)

			//case exists
			if node.Operator == "exists" {
				if len(lefts) > 0 {
					results = append(results, value.Index(i))
				}
				continue
			}

			if err != nil {
				return input, err
			}

			var left, right interface{}
			switch {
			case len(lefts) == 0:
				continue
			case len(lefts) > 1:
				return input, fmt.Errorf("can only compare one element at a time")
			}
			left = lefts[0].Interface()

			rights, err := j.evalList(temp, node.Right)
			if err != nil {
				return input, err
			}
			switch {
			case len(rights) == 0:
				continue
			case len(rights) > 1:
				return input, fmt.Errorf("can only compare one element at a time")
			}
			right = rights[0].Interface()

			pass := false
			switch node.Operator {
			case "<":
				pass, err = template.Less(left, right)
			case ">":
				pass, err = template.Greater(left, right)
			case "==":
				pass, err = template.Equal(left, right)
			case "!=":
				pass, err = template.NotEqual(left, right)
			case "<=":
				pass, err = template.LessEqual(left, right)
			case ">=":
				pass, err = template.GreaterEqual(left, right)
			default:
				return results, fmt.Errorf("unrecognized filter operator %s", node.Operator)
			}
			if err != nil {
				return results, err
			}
			if pass {
				results = append(results, value.Index(i))
			}
		}
	}
	return results, nil
}

// evalToText translates reflect value to corresponding text
func (j *JSONPath) evalToText(v reflect.Value) ([]byte, error) {
	iface, ok := template.PrintableValue(v)
	if !ok {
		return nil, fmt.Errorf("can't print type %s", v.Type())
	}
	var buffer bytes.Buffer
	fmt.Fprint(&buffer, iface)
	return buffer.Bytes(), nil
}
