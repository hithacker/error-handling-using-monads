(ns order-clj.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :refer [response not-found]]
            [cheshire.core :refer :all]
            [cats.core :as m]
            [cats.monad.either :as either]
            [cats.monad.maybe :as maybe]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.conversion :refer [from-db-object]]
            monger.json)
  (:import (org.bson.types ObjectId)))

(defonce conn (mg/connect))
(defonce db (mg/get-db conn "order"))

(defn disconnect []
  (mg/disconnect conn))

;;Defining entities
(defn order [order]
  (dissoc order :_id :customerOrgId))

(defn orders [orders]
  (map order orders))

(defn orderline-model
  [quote orderline]
  (-> orderline
      (assoc
        :product (:product quote)
        :price (:price quote)
        :campaign (:campaign quote))
      (dissoc
        :_id
        :quote)))

;;;Monadic functions

(defn mfind-order [order-id]
  (cond
    (= order-id 1) (either/right {:orderId order-id :price 725.0 :status "CONFIRMED"})
    (= order-id 2) (either/right {:orderId order-id :price 540.0 :status "DRAFT"})
    :else (either/left {:status 404 :message "Order not found"})))

(defn mcreate-orderline [order]
  (cond
    (= (:status order) "DRAFT")
      (either/right {:orderLineId 123})
    :else
      (either/left {:status 400 :message "New OrderLine can only be added to order with status DRAFT"})))

(defn mfuncreate-orderline []
  (fn [order]
    (cond
      (= (:status order) "DRAFT")
      (either/right {:orderLineId 123})
      :else
      (either/left {:status 400 :message "New OrderLine can only be added to order with status DRAFT"}))))

(defn create-orderline [order-id]
  (m/mlet [a (mfind-order order-id)
           b (mcreate-orderline a)]
          (m/return b)))


;;Defining common functions

(def ORDER_NOT_FOUND
  "No order found for given customer org Id: %s with order Id: %s")

(defn fetch-quote-id-nested [orderline]
  (.toString
    (.getId
      (:quote
        (orderline)))))

(defn fetch-quote-id [orderline]
  (-> orderline
      :quote
      .getId
      .toString))

(defn fetch-quote [id]
  (let [coll "quotes"]
    (mc/find-one-as-map db coll {:_id (ObjectId. id)})))

(defn compose-orderline
  [orderline]
  (-> orderline
      fetch-quote-id
      fetch-quote
      (orderline-model orderline)))

(defn fetch-order-lines [order-id]
  (response
    (map compose-orderline
         (mc/find-maps db "orderlines" {:orderId order-id}))))

;;Defining route functions
(defn get-orders [customerOrgId]
  (let [conn (mg/connect)
        db (mg/get-db conn "order")
        coll "orders"]
    (response
      (orders
        (mc/find-maps
          db coll {:customerOrgId customerOrgId})))))

(defn get-order [customerOrgId order-id]
  (let [conn (mg/connect)
        db (mg/get-db conn "order")
        coll "orders"]
    (if-let [order
             (mc/find-one-as-map
               db coll {:customerOrgId customerOrgId :orderId order-id})]
      (response order)
      (not-found {:message (format ORDER_NOT_FOUND customerOrgId order-id)}))))

;;Defining route handlers
(defroutes app-routes
   (context "/customers/:customerOrgId/orders" [customerOrgId]
     (GET "/" []
       (get-orders customerOrgId))
     (GET "/:order-id" [order-id]
       (get-order customerOrgId order-id))
     (GET "/:order-id/lines" [order-id]
       (fetch-order-lines order-id))
     (route/not-found "Not Found")))

(def app
  (-> app-routes
      (wrap-json-response)
      (wrap-defaults api-defaults)))
