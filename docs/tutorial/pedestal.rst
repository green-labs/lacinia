Lacinia Pedestal
================

Working from the REPL is important, but ultimately GraphQL exists to provide a web-based API.
Fortunately, it is very easy to get your Lacinia application up on the web, on top of
the `Pedestal <http://pedestal.io/>`_ web tier, using
`Lacinia-Pedestal <https://github.com/walmartlabs/lacinia-pedestal>`_.

In addition, for free, we get GraphQL's own REPL: `GraphiQL <https://github.com/graphql/graphiql>`_.

Add Dependencies
----------------

.. ex:: pedestal project.clj
   :emphasize-lines: 8-9

We've added two libraries: ``lacinia-pedestal`` and ``io.aviso/logging``.

The former brings in quite a few dependencies, including Pedestal, and the underlying
`Jetty <https://www.eclipse.org/jetty/>`_ layer that Pedestal builds upon.

The ``io.aviso/logging`` library sets up
`Logback <https://logback.qos.ch/>`_ as the logging library.

Some Configuration
------------------

For best results, we can configure Logback; this keeps startup and request handling
from being very chatty:

.. ex:: pedestal dev-resources/logback-test.xml

This configuration hides log events below the warning level (that is, debug and info events).
If any warnings or errors do occur, minimal output is sent to the console.

A :file:`logback-test.xml` takes precendence over the production :file:`logback.xml` configuration
we will eventually supply.

User Namespace
--------------

We'll add more scaffolding to the ``user`` namespace, to make it possible to start and stop
the Pedestal server.

.. ex:: pedestal dev-resources/user.clj
   :emphasize-lines: 5-7,35-

This new code is almost entirely boilerplate for Pedestal and for Lacinia-Pedestal.
The core function is ``com.walmartlabs.lacinia.pedestal/pedestal-service`` which is passed the compiled schema
and a map of options, and returns a Pedestal service map which is then used
to define the Pedestal server.

GraphiQL is not enabled by default; it is opt-in, and should generally only be enabled
for development servers, or behind a firewall that limits access from the outside world.

Lacinia-Pedestal services GraphQL requests at the ``/graphql`` path.
The default port is 8888.
It handles both GET and POST requests. We'll get to the details later.

The ``/`` and ``/index.html`` paths, and related JavaScript and CSS resources, can only be accessed
when GraphiQL is enabled.


Starting The Server
-------------------

With the above scaffolding in place, it is just a matter of starting the REPL and evaluating ``(start)``.

At this point, your web browser should open to the GraphiQL application:

.. image:: /_static/tutorial/graphiql-initial.png

.. tip::

   It's really worth following along with this section, especially if you haven't played
   with GraphiQL before. GraphiQL assists you with formatting, pop-up help, flagging of errors,
   and automatic input completion. It makes for quite the demo!

Running Queries
---------------

We can now type a query into the large text area on the left and then click
the right arrow button (or type ``Command+Enter``), and see pretty-printed JSON on the right:

.. image:: /_static/tutorial/graphiql-basic-query.png

Notice that the URL bar in the browser has updated: it contains the full query string.
This means that you can bookmark a query you like for later (though it's easier to do that using
the ``History`` button).
Alternately, and more importantly, you can copy that URL and provide it to other developers.
They can start up the application on their workstations and see exactly what you see, a real boon for
describing and diagnosing problems.

This approach works even better when you keep a GraphQL server running on a shared staging server.
On split [#split]_ teams, the developers creating the application can easily explore the interface exposed
by the GraphQL server, even before writing their first line of code.

Trust me, they love that.

Documentation Browser
---------------------

The ``< Docs`` button on the right opens the documentation browser:

.. image:: /_static/tutorial/graphiql-doc-browser.png

The documentation browser is invaluable: it allows you to navigate around your schema, drilling down
to queries, objects, and fields to see a summary of their
declaration, as well as their documentation - those
``:documentation`` values we added way back
:doc:`at the beginning <init-schema>`.

Take some time to learn what GraphiQL can do for you.

.. [#split] That is, where one team or set of developers `just` does the user interface,
   and the other team `just` does the server side (including Lacinia). Part of the
   value proposition for GraphQL is how clean and uniform this split can be.